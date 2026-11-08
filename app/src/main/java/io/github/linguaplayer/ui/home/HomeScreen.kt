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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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

data class HomeState(
    val pairName: String = "",
    val entryCount: Int = 0,
    val recent: List<PlaylistWithCount> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(c: AppContainer) : ViewModel() {
    val state = c.activePair.flatMapLatest { pair ->
        val count = if (pair == null) flowOf(0) else c.entries.observeCountForPair(pair.id)
        combine(count, c.playlists.observeRecent(5)) { n, recent ->
            HomeState(pair?.displayName().orEmpty(), n, recent)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@Composable
fun HomeScreen(
    onOpenLibrary: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val c = appContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
            Text(state.pairName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Card(
                onClick = onOpenLibrary,
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null, Modifier.size(40.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(R.string.home_library_card), style = MaterialTheme.typography.titleLarge)
                        Text(pluralStringResource(R.plurals.entries_count, state.entryCount, state.entryCount))
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
        items(state.recent, key = { it.playlist.id }) { p ->
            PlaylistListItem(p, Modifier.clickable { onOpenPlaylist(p.playlist.id) })
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
