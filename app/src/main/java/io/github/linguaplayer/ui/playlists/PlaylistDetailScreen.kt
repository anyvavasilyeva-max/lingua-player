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

    fun move(from: Int, to: Int) = viewModelScope.launch { c.playlists.move(id, from, to) }
    fun remove(entryId: Long) = viewModelScope.launch { c.playlists.removeEntries(id, listOf(entryId)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlistId: Long, onBack: () -> Unit, onOpenEntry: (Long) -> Unit) {
    val c = appContainer()
    val vm: PlaylistDetailViewModel = viewModel(key = "playlist-$playlistId") { PlaylistDetailViewModel(c, playlistId) }
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
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
            )
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
                        trailing = if (normal) {
                            {
                                EntryOrderMenu(
                                    canUp = index > 0,
                                    canDown = index < list.lastIndex,
                                    onUp = { vm.move(index, index - 1) },
                                    onDown = { vm.move(index, index + 1) },
                                    onRemove = { vm.remove(item.entry.id) },
                                )
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryOrderMenu(canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
