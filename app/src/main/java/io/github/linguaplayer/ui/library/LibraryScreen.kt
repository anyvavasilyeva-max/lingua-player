package io.github.linguaplayer.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.R
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.PlaylistEntity
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.EntryWithTags
import io.github.linguaplayer.ui.components.ConfirmDialog
import io.github.linguaplayer.ui.components.EmptyState
import io.github.linguaplayer.ui.components.EntryRow
import io.github.linguaplayer.ui.components.PlaylistPickerDialog
import io.github.linguaplayer.ui.components.TagPickerDialog
import io.github.linguaplayer.ui.components.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryFilterState(
    val text: String = "",
    val type: TypeFilter = TypeFilter.ALL,
    val tagIds: Set<Long> = emptySet(),
    val tagMode: TagMatchMode = TagMatchMode.ANY,
)

/** Сообщение для snackbar после массовой операции. */
sealed interface LibraryMessage {
    data class TagsUpdated(val count: Int) : LibraryMessage
    data class AddedToPlaylist(val name: String) : LibraryMessage
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(LibraryFilterState())
    val selected = MutableStateFlow<Set<Long>>(emptySet())
    val message = MutableStateFlow<LibraryMessage?>(null)

    /** Все теги — для назначения записям. */
    val tags = c.tags.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Теги, используемые в активной паре, — для фильтров (теги другой пары не показываются). */
    val pairTags = c.activePair.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else c.tags.observeUsedInPair(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null — данные ещё загружаются. */
    val entries = combine(c.activePair, filter.debounce(150)) { pair, f -> pair to f }
        .flatMapLatest { (pair, f) ->
            c.entries.observeEntries(
                EntryFilter(pairId = pair?.id ?: -1, text = f.text, type = f.type, tagIds = f.tagIds, tagMode = f.tagMode),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setText(t: String) = filter.update { it.copy(text = t) }
    fun setType(t: TypeFilter) = filter.update { it.copy(type = t) }
    fun toggleTag(id: Long) = filter.update { it.copy(tagIds = if (id in it.tagIds) it.tagIds - id else it.tagIds + id) }
    fun toggleMode() = filter.update {
        it.copy(tagMode = if (it.tagMode == TagMatchMode.ANY) TagMatchMode.ALL else TagMatchMode.ANY)
    }
    fun clearTags() = filter.update { it.copy(tagIds = emptySet()) }

    // --- Выбор нескольких записей ---
    fun toggleSelected(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun selectAllVisible() = selected.update { entries.value.orEmpty().map { e -> e.entry.id }.toSet() }
    fun clearSelection() { selected.value = emptySet() }

    fun addTags(tagIds: Set<Long>) = viewModelScope.launch {
        val ids = selected.value
        c.entries.addTags(ids, tagIds)
        message.value = LibraryMessage.TagsUpdated(ids.size)
        clearSelection()
    }

    fun removeTags(tagIds: Set<Long>) = viewModelScope.launch {
        val ids = selected.value
        c.entries.removeTags(ids, tagIds)
        message.value = LibraryMessage.TagsUpdated(ids.size)
        clearSelection()
    }

    fun addToPlaylist(p: PlaylistEntity) = viewModelScope.launch {
        // Порядок добавления — как в текущем списке библиотеки.
        val order = entries.value.orEmpty().map { it.entry.id }.filter { it in selected.value }
        c.playlists.addEntries(p.id, order)
        message.value = LibraryMessage.AddedToPlaylist(p.name)
        clearSelection()
    }

    fun deleteSelected() = viewModelScope.launch {
        c.entries.delete(selected.value)
        clearSelection()
    }

    fun createTag(name: String, onCreated: (Long) -> Unit) = viewModelScope.launch {
        runCatching { c.tags.getOrCreate(name) }.getOrNull()?.let { onCreated(it.id) }
    }

    suspend fun normalPlaylists() = c.activePair.first()?.let { c.playlists.normalPlaylists(it.id) }.orEmpty()

    /** Слушать найденные записи (или только выбранные, если есть выбор). */
    fun play(title: String, onStarted: () -> Unit) = viewModelScope.launch {
        val all = entries.value.orEmpty().map { it.entry }
        val sel = selected.value
        val list = if (sel.isEmpty()) all else all.filter { it.id in sel }
        if (list.isEmpty()) return@launch
        c.startPlayback(title, list)
        clearSelection()
        onStarted()
    }

    fun consumeMessage() { message.value = null }
}

private enum class LibraryDialog { ADD_TAGS, REMOVE_TAGS, PLAYLIST, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenEntry: (Long) -> Unit,
    onAddEntry: () -> Unit,
    onManageTags: () -> Unit,
    onImport: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val c = appContainer()
    val vm: LibraryViewModel = viewModel { LibraryViewModel(c) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val pairTags by vm.pairTags.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val selecting = selected.isNotEmpty()
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var playlists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    val libraryTitle = stringResource(R.string.nav_library)

    val messageText = when (val m = message) {
        is LibraryMessage.TagsUpdated -> stringResource(R.string.selection_done_tags, m.count)
        is LibraryMessage.AddedToPlaylist -> stringResource(R.string.selection_done_playlist, m.name)
        null -> null
    }
    LaunchedEffect(messageText) {
        if (messageText != null) { vm.consumeMessage(); snackbar.showSnackbar(messageText) }
    }
    LaunchedEffect(dialog) {
        if (dialog == LibraryDialog.PLAYLIST) playlists = vm.normalPlaylists()
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) }
                    },
                    title = { Text(stringResource(R.string.selection_count, selected.size), maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(onClick = { vm.play(libraryTitle, onOpenPlayer) }) {
                            Icon(Icons.Outlined.PlayArrow, stringResource(R.string.player_listen))
                        }
                        IconButton(onClick = vm::selectAllVisible) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.selection_all)) }
                        IconButton(onClick = { dialog = LibraryDialog.ADD_TAGS }) {
                            Icon(Icons.Outlined.NewLabel, stringResource(R.string.selection_add_tags))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.selection_remove_tags)) },
                                    onClick = { menuOpen = false; dialog = LibraryDialog.REMOVE_TAGS },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.selection_add_playlist)) },
                                    onClick = { menuOpen = false; dialog = LibraryDialog.PLAYLIST },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete)) },
                                    onClick = { menuOpen = false; dialog = LibraryDialog.DELETE },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_library)) },
                    actions = {
                        IconButton(onClick = { vm.play(libraryTitle, onOpenPlayer) }, enabled = !entries.isNullOrEmpty()) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.library_play))
                        }
                        IconButton(onClick = onImport) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.import_action))
                        }
                        IconButton(onClick = onManageTags) {
                            Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = stringResource(R.string.library_tags))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = onAddEntry,
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(stringResource(R.string.library_add)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = filter.text,
                onValueChange = vm::setText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.library_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (filter.text.isNotEmpty()) {
                        IconButton(onClick = { vm.setText("") }) { Icon(Icons.Outlined.Clear, null) }
                    }
                },
                singleLine = true,
            )
            FilterBar(filter, pairTags, vm)
            HorizontalDivider()
            val list = entries
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(stringResource(R.string.library_empty))
                else -> EntryList(
                    list,
                    selected,
                    onClick = { id -> if (selecting) vm.toggleSelected(id) else onOpenEntry(id) },
                    onLongClick = vm::toggleSelected,
                )
            }
        }
    }

    when (dialog) {
        LibraryDialog.ADD_TAGS -> TagPickerDialog(
            title = stringResource(R.string.selection_add_tags),
            tags = tags,
            onDismiss = { dialog = null },
            onConfirm = { vm.addTags(it); dialog = null },
            onCreateTag = vm::createTag,
        )
        LibraryDialog.REMOVE_TAGS -> TagPickerDialog(
            title = stringResource(R.string.selection_remove_tags),
            tags = pairTags,
            onDismiss = { dialog = null },
            onConfirm = { vm.removeTags(it); dialog = null },
        )
        LibraryDialog.PLAYLIST -> PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = { dialog = null },
            onPick = { vm.addToPlaylist(it); dialog = null },
        )
        LibraryDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.selection_delete_confirm, selected.size),
            onDismiss = { dialog = null },
            onConfirm = { vm.deleteSelected(); dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun FilterBar(filter: LibraryFilterState, tags: List<TagEntity>, vm: LibraryViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(filter.type == TypeFilter.ALL, { vm.setType(TypeFilter.ALL) }, { Text(stringResource(R.string.filter_all)) })
        }
        item {
            FilterChip(filter.type == TypeFilter.WORDS, { vm.setType(TypeFilter.WORDS) }, { Text(stringResource(R.string.filter_words)) })
        }
        item {
            FilterChip(filter.type == TypeFilter.PHRASES, { vm.setType(TypeFilter.PHRASES) }, { Text(stringResource(R.string.filter_phrases)) })
        }
        if (filter.tagIds.size >= 2) {
            item {
                FilterChip(
                    selected = true,
                    onClick = vm::toggleMode,
                    label = {
                        Text(stringResource(if (filter.tagMode == TagMatchMode.ANY) R.string.filter_tag_any else R.string.filter_tag_all))
                    },
                )
            }
        }
        items(tags, key = { it.id }) { tag ->
            FilterChip(tag.id in filter.tagIds, { vm.toggleTag(tag.id) }, { Text("#" + tag.name) })
        }
        if (filter.tagIds.isNotEmpty()) {
            item { FilterChip(false, vm::clearTags, { Text(stringResource(R.string.filter_clear)) }) }
        }
    }
}

@Composable
private fun EntryList(
    list: List<EntryWithTags>,
    selected: Set<Long>,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        item {
            Text(
                pluralStringResource(R.plurals.entries_count, list.size, list.size) +
                    if (selected.isEmpty()) " · " + stringResource(R.string.selection_hint) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        items(list, key = { it.entry.id }) { item ->
            EntryRow(
                item,
                onClick = { onClick(item.entry.id) },
                selected = item.entry.id in selected,
                onLongClick = { onLongClick(item.entry.id) },
            )
        }
    }
}
