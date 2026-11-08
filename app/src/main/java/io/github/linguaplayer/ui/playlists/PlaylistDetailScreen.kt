package io.github.linguaplayer.ui.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.data.repo.SmartRules
import io.github.linguaplayer.ui.components.EmptyState
import io.github.linguaplayer.ui.components.EntryRow
import io.github.linguaplayer.ui.components.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val playlist = c.playlists.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val entries = c.playlists.observeEntries(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { c.playlists.markOpened(id) }
    }

    val pairs = c.languagePairs.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags = c.tags.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun smartTagIds() = c.playlists.smartTagIds(id)

    fun updateSmart(name: String, rules: SmartRules) = viewModelScope.launch {
        runCatching {
            c.playlists.rename(id, name)
            c.playlists.updateSmartRules(id, rules)
        }
    }

    /** Запуск воспроизведения плейлиста (с начала или с выбранной записи). */
    fun play(startEntryId: Long? = null, onStarted: () -> Unit) = viewModelScope.launch {
        val p = playlist.value ?: return@launch
        val list = entries.value ?: return@launch
        c.startPlayback(p.name, list.map { it.entry }, p.id, startEntryId)
        onStarted()
    }

    fun move(from: Int, to: Int) = viewModelScope.launch { c.playlists.move(id, from, to) }
    fun remove(entryId: Long) = viewModelScope.launch { c.playlists.removeEntries(id, listOf(entryId)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlistId: Long, onBack: () -> Unit, onOpenEntry: (Long) -> Unit, onOpenPlayer: () -> Unit) {
    val c = appContainer()
    val vm: PlaylistDetailViewModel = viewModel(key = "playlist-$playlistId") { PlaylistDetailViewModel(c, playlistId) }
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    var editRules by remember { mutableStateOf<SmartRules?>(null) }
    val scope = rememberCoroutineScope()
    val normal = playlist?.kind == PlaylistKind.NORMAL

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(playlist?.name.orEmpty())
                        entries?.let {
                            Text(
                                pluralStringResource(R.plurals.entries_count, it.size, it.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    val p = playlist
                    if (p != null && p.kind == PlaylistKind.SMART) {
                        IconButton(onClick = {
                            scope.launch { editRules = SmartRules(p.pairId, p.smartType, vm.smartTagIds(), p.smartTagMode) }
                        }) { Icon(Icons.Outlined.Tune, stringResource(R.string.smart_edit)) }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!entries.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.play(onStarted = onOpenPlayer) },
                    icon = { Icon(Icons.Outlined.PlayArrow, null) },
                    text = { Text(stringResource(R.string.player_listen)) },
                )
            }
        },
    ) { padding ->
        val list = entries
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                stringResource(if (normal) R.string.playlist_empty else R.string.playlist_smart_empty),
                Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                itemsIndexed(list, key = { _, it -> it.entry.id }) { index, item ->
                    EntryRow(
                        item,
                        onClick = { onOpenEntry(item.entry.id) },
                        trailing = {
                            EntryOrderMenu(
                                editable = normal,
                                canUp = index > 0,
                                canDown = index < list.lastIndex,
                                onUp = { vm.move(index, index - 1) },
                                onDown = { vm.move(index, index + 1) },
                                onRemove = { vm.remove(item.entry.id) },
                                onPlayFromHere = { vm.play(item.entry.id, onOpenPlayer) },
                            )
                        },
                    )
                }
            }
        }
    }

    editRules?.let { rules ->
        PlaylistEditorDialog(
            title = stringResource(R.string.smart_edit),
            pairs = pairs,
            tags = tags,
            initialName = playlist?.name.orEmpty(),
            initialKind = PlaylistKind.SMART,
            initialRules = rules,
            kindEditable = false,
            onDismiss = { editRules = null },
            onConfirm = { name, _, r -> editRules = null; if (r != null) vm.updateSmart(name, r) },
        )
    }
}

@Composable
private fun EntryOrderMenu(
    editable: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
    onPlayFromHere: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.play_from_here)) },
                onClick = { open = false; onPlayFromHere() },
            )
            if (!editable) return@DropdownMenu
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_move_up)) },
                enabled = canUp,
                onClick = { open = false; onUp() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_move_down)) },
                enabled = canDown,
                onClick = { open = false; onDown() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_remove_entry)) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}
