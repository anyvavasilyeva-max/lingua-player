package io.github.linguaplayer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.width
import io.github.linguaplayer.ui.playlists.PlaylistTile
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.map
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import io.github.linguaplayer.data.db.PlaylistWithCount
import io.github.linguaplayer.data.repo.displayName
import io.github.linguaplayer.ui.components.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HomeState(
    val pairName: String = "",
    val entryCount: Int = 0,
    val recent: List<PlaylistWithCount> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val pairs = c.languagePairs.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activePairId = c.activePair.map { it?.id }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun selectPair(id: Long) = viewModelScope.launch { c.settings.setActivePair(id) }

    fun playLibrary(title: String, onStarted: () -> Unit) = viewModelScope.launch {
        val pair = c.activePair.first() ?: return@launch
        val list = c.entries.getEntries(io.github.linguaplayer.data.db.EntryFilter(pairId = pair.id))
        if (list.isEmpty()) return@launch
        c.startPlayback(title, list)
        onStarted()
    }

    val state = c.activePair.flatMapLatest { pair ->
        val count = if (pair == null) flowOf(0) else c.entries.observeCountForPair(pair.id)
        val recent = if (pair == null) flowOf(emptyList()) else c.playlists.observeRecent(pair.id, 5)
        combine(count, recent) { n, recent ->
            HomeState(pair?.displayName().orEmpty(), n, recent)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@Composable
fun HomeScreen(
    onOpenLibrary: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onImport: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val c = appContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val libraryTitle = stringResource(R.string.nav_library)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
            PairSwitcher(vm, state.pairName)
            Spacer(Modifier.height(16.dp))
            Card(
                onClick = onOpenLibrary,
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null, Modifier.size(40.dp))
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(stringResource(R.string.home_library_card), style = MaterialTheme.typography.titleLarge)
                        Text(pluralStringResource(R.plurals.entries_count, state.entryCount, state.entryCount))
                    }
                    if (state.entryCount > 0) {
                        FilledIconButton(onClick = { vm.playLibrary(libraryTitle, onOpenPlayer) }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Outlined.PlayArrow, stringResource(R.string.player_listen))
                        }
                    }
                }
            }
            if (state.entryCount == 0) {
                Text(
                    stringResource(R.string.home_empty_library),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Icon(Icons.Outlined.FileUpload, null)
                Text(stringResource(R.string.import_title), Modifier.padding(start = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenPlaylists) { Text(stringResource(R.string.home_all_playlists)) }
            }
        }
        if (state.recent.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.recent.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.recent, key = { it.playlist.id }) { p ->
                        PlaylistTile(p, onClick = { onOpenPlaylist(p.playlist.id) }, modifier = Modifier.width(132.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistListItem(p: PlaylistWithCount, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    val smart = p.playlist.kind == PlaylistKind.SMART
    ListItem(
        modifier = modifier,
        leadingContent = {
            Icon(if (smart) Icons.Outlined.AutoAwesome else Icons.AutoMirrored.Outlined.QueueMusic, null)
        },
        headlineContent = { Text(p.playlist.name) },
        supportingContent = {
            Text(
                if (smart) stringResource(R.string.playlist_smart)
                else pluralStringResource(R.plurals.entries_count, p.entryCount, p.entryCount),
            )
        },
        trailingContent = trailing,
    )
}

/** Активная языковая пара; при нескольких парах — меню для быстрого переключения. */
@Composable
private fun PairSwitcher(vm: HomeViewModel, pairName: String) {
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val activeId by vm.activePairId.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { if (pairs.size > 1) open = true },
            label = { Text(pairName) },
            leadingIcon = { Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp)) },
            trailingIcon = if (pairs.size > 1) { { Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.home_switch_pair)) } } else null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            pairs.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.displayName()) },
                    leadingIcon = { RadioButton(selected = p.id == activeId, onClick = null) },
                    onClick = { open = false; vm.selectPair(p.id) },
                )
            }
        }
    }
}
