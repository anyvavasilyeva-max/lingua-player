package io.github.linguaplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.linguaplayer.R
import io.github.linguaplayer.player.PlayerEngine

/** Полоска «сейчас играет» над нижней навигацией. */
@Composable
fun MiniPlayer(player: PlayerEngine, onOpen: () -> Unit) {
    val st by player.state.collectAsStateWithLifecycle()
    val item = st.current ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.source, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.translation} · ${st.index + 1}/${st.queue.size}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = player::togglePlay) {
                Icon(
                    if (st.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    stringResource(if (st.isPlaying) R.string.player_pause else R.string.player_play),
                )
            }
            IconButton(onClick = player::next, enabled = st.index < st.queue.lastIndex) {
                Icon(Icons.Outlined.SkipNext, stringResource(R.string.player_next))
            }
        }
    }
}
