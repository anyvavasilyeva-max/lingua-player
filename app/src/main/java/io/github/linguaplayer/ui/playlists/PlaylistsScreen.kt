package io.github.linguaplayer.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import io.github.linguaplayer.data.db.PlaylistWithCount
import io.github.linguaplayer.ui.components.PlaylistCover
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import io.github.linguaplayer.data.db.PlaylistEntity
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.data.repo.SmartRules
import io.github.linguaplayer.ui.components.ConfirmDialog
import io.github.linguaplayer.ui.components.EmptyState
import io.github.linguaplayer.ui.components.TextInputDialog
import io.github.linguaplayer.ui.components.appContainer
import io.github.linguaplayer.ui.home.PlaylistListItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val c: AppContainer) : ViewModel() {
    val playlists = c.playlists.observeAll().map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pairs = c.languagePairs.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags = c.tags.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, kind: PlaylistKind, rules: SmartRules?, onCreated: (Long) -> Unit) = viewModelScope.launch {
        runCatching { c.playlists.create(name, kind, rules) }.onSuccess(onCreated)
    }
    fun rename(id: Long, name: String) = viewModelScope.launch { runCatching { c.playlists.rename(id, name) } }
    fun delete(id: Long) = viewModelScope.launch { c.playlists.delete(id) }

    val grid = c.settings.playlistsGrid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setGrid(v: Boolean) = viewModelScope.launch { c.settings.setPlaylistsGrid(v) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(onOpenPlaylist: (Long) -> Unit) {
    val c = appContainer()
    val vm: PlaylistsViewModel = viewModel { PlaylistsViewModel(c) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }
    val grid by vm.grid.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_playlists)) },
                actions = {
                    IconButton(onClick = { showCreate = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.playlist_new)) }
                    IconToggleButton(checked = !grid, onCheckedChange = { vm.setGrid(false) }) {
                        Icon(Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.playlists_view_list))
                    }
                    IconToggleButton(checked = grid, onCheckedChange = { vm.setGrid(true) }) {
                        Icon(Icons.Outlined.GridView, stringResource(R.string.playlists_view_grid))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.playlist_new)) },
            )
        },
    ) { padding ->
        val list = playlists
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(stringResource(R.string.playlists_empty), Modifier.padding(padding))
            grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(list, key = { it.playlist.id }) { p ->
                    PlaylistTile(
                        p,
                        onClick = { onOpenPlaylist(p.playlist.id) },
                        menu = { PlaylistMenu(onRename = { renaming = p.playlist }, onDelete = { deleting = p.playlist }) },
                    )
                }
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(list, key = { it.playlist.id }) { p ->
                    PlaylistListItem(
                        p,
                        Modifier.clickable { onOpenPlaylist(p.playlist.id) },
                        trailing = {
                            PlaylistMenu(
                                onRename = { renaming = p.playlist },
                                onDelete = { deleting = p.playlist },
                            )
                        },
                    )
                }
            }
        }
    }

    if (showCreate) {
        PlaylistEditorDialog(
            title = stringResource(R.string.playlist_new),
            pairs = pairs,
            tags = tags,
            onDismiss = { showCreate = false },
            onConfirm = { name, kind, rules ->
                showCreate = false
                vm.create(name, kind, rules) { id -> if (kind == PlaylistKind.SMART) onOpenPlaylist(id) }
            },
        )
    }
    renaming?.let { p ->
        TextInputDialog(
            title = stringResource(R.string.action_rename),
            initial = p.name,
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(p.id, it); renaming = null },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.playlist_delete_confirm, p.name),
            onDismiss = { deleting = null },
            onConfirm = { vm.delete(p.id); deleting = null },
        )
    }
}

/** Плитка плейлиста: обложка + число записей, меню в углу. */
@Composable
fun PlaylistTile(p: PlaylistWithCount, onClick: () -> Unit, modifier: Modifier = Modifier, menu: (@Composable () -> Unit)? = null) {
    Column(modifier.clickable(onClick = onClick)) {
        Box {
            PlaylistCover(p.playlist, Modifier.fillMaxWidth().aspectRatio(1f))
            if (menu != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) { menu() }
            }
        }
        Text(
            if (p.playlist.kind == PlaylistKind.SMART) stringResource(R.string.playlist_smart)
            else pluralStringResource(R.plurals.entries_count, p.entryCount, p.entryCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

@Composable
fun PlaylistMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { open = false; onDelete() })
        }
    }
}
