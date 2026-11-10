package io.github.linguaplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.R
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.data.db.PlaylistEntity
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.data.repo.displayName
import io.github.linguaplayer.data.repo.languageDisplayName
import io.github.linguaplayer.ui.components.ConfirmDialog
import io.github.linguaplayer.ui.components.TextInputDialog
import io.github.linguaplayer.ui.components.appContainer
import io.github.linguaplayer.ui.components.errorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EntryEditState(
    val loaded: Boolean = false,
    val id: Long = 0,
    val pair: LanguagePairEntity? = null,
    val source: String = "",
    val translation: String = "",
    val type: EntryType = EntryType.WORD,
    val tagIds: Set<Long> = emptySet(),
    val playlistIds: Set<Long> = emptySet(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val error: Throwable? = null,
    val done: Boolean = false,
)

class EntryEditViewModel(private val c: AppContainer, entryId: Long) : ViewModel() {
    val state = MutableStateFlow(EntryEditState())
    val tags = c.tags.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val details = if (entryId != 0L) c.entries.getDetails(entryId) else null
            // Только плейлисты пары этой записи (для новой записи — активной пары).
            val pairId = details?.entry?.pairId ?: c.activePair.first()?.id
            val playlists = pairId?.let { c.playlists.normalPlaylists(it) }.orEmpty()
            if (details != null) {
                state.value = EntryEditState(
                    loaded = true,
                    id = details.entry.id,
                    pair = c.languagePairs.get(details.entry.pairId),
                    source = details.entry.source,
                    translation = details.entry.translation,
                    type = details.entry.type,
                    tagIds = details.tagIds,
                    playlistIds = details.playlistIds,
                    playlists = playlists,
                )
            } else {
                state.value = EntryEditState(loaded = true, pair = c.activePair.first(), playlists = playlists)
            }
        }
    }

    fun setSource(v: String) = state.update { it.copy(source = v) }
    fun setTranslation(v: String) = state.update { it.copy(translation = v) }
    fun setType(v: EntryType) = state.update { it.copy(type = v) }
    fun toggleTag(id: Long) = state.update { it.copy(tagIds = if (id in it.tagIds) it.tagIds - id else it.tagIds + id) }
    fun togglePlaylist(id: Long) =
        state.update { it.copy(playlistIds = if (id in it.playlistIds) it.playlistIds - id else it.playlistIds + id) }
    fun clearError() = state.update { it.copy(error = null) }

    fun createTag(name: String, onCreated: () -> Unit) = viewModelScope.launch {
        runCatching { c.tags.create(name) }
            .onSuccess { tag -> state.update { it.copy(tagIds = it.tagIds + tag.id) }; onCreated() }
            .onFailure { e -> state.update { it.copy(error = e) } }
    }

    fun save() = viewModelScope.launch {
        val s = state.value
        val pair = s.pair ?: return@launch
        runCatching {
            c.entries.save(EntryDraft(s.id, pair.id, s.source, s.translation, s.type, s.tagIds, s.playlistIds))
        }.onSuccess { state.update { it.copy(done = true) } }
            .onFailure { e -> state.update { it.copy(error = e) } }
    }

    fun delete() = viewModelScope.launch {
        val id = state.value.id
        if (id != 0L) c.entries.delete(listOf(id))
        state.update { it.copy(done = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditScreen(entryId: Long, onDone: () -> Unit) {
    val c = appContainer()
    val vm: EntryEditViewModel = viewModel(key = "entry-$entryId") { EntryEditViewModel(c, entryId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showNewTag by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(s.done) { if (s.done) onDone() }
    val err = s.error
    val errMsg = err?.let { errorText(it) }
    LaunchedEffect(err) {
        if (errMsg != null && !showNewTag) { snackbar.showSnackbar(errMsg); vm.clearError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.id == 0L) R.string.entry_new else R.string.entry_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::save, enabled = s.loaded && s.source.isNotBlank() && s.translation.isNotBlank()) {
                        Icon(Icons.Outlined.Check, stringResource(R.string.action_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!s.loaded) return@Scaffold
        val pair = s.pair
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair?.let {
                Text(
                    stringResource(R.string.entry_pair, it.displayName()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = s.source,
                onValueChange = vm::setSource,
                label = { Text(stringResource(R.string.entry_source, pair?.let { languageDisplayName(it.sourceLang) }.orEmpty())) },
                textStyle = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.translation,
                onValueChange = vm::setTranslation,
                label = { Text(stringResource(R.string.entry_translation, pair?.let { languageDisplayName(it.targetLang) }.orEmpty())) },
                supportingText = { Text(stringResource(R.string.entry_translation_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.entry_type), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EntryType.entries.forEachIndexed { i, type ->
                    SegmentedButton(
                        selected = s.type == type,
                        onClick = { vm.setType(type) },
                        shape = SegmentedButtonDefaults.itemShape(i, EntryType.entries.size),
                    ) { Text(stringResource(if (type == EntryType.WORD) R.string.type_word else R.string.type_phrase)) }
                }
            }

            Text(stringResource(R.string.entry_tags), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    FilterChip(tag.id in s.tagIds, { vm.toggleTag(tag.id) }, { Text(tag.name) })
                }
                AssistChip(
                    onClick = { showNewTag = true },
                    label = { Text(stringResource(R.string.entry_new_tag)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                )
            }

            Text(stringResource(R.string.entry_playlists), style = MaterialTheme.typography.titleSmall)
            if (s.playlists.isEmpty()) {
                Text(stringResource(R.string.entry_no_playlists), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.playlists.forEach { p ->
                    FilterChip(p.id in s.playlistIds, { vm.togglePlaylist(p.id) }, { Text(p.name) })
                }
            }

            if (s.id != 0L) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Delete, null)
                    Text(stringResource(R.string.action_delete), Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (showNewTag) {
        TextInputDialog(
            title = stringResource(R.string.entry_new_tag),
            confirmText = stringResource(R.string.action_create),
            error = errMsg,
            onDismiss = { showNewTag = false; vm.clearError() },
            onConfirm = { vm.createTag(it) { showNewTag = false } },
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.entry_delete_confirm),
            onDismiss = { showDelete = false },
            onConfirm = { showDelete = false; vm.delete() },
        )
    }
}
