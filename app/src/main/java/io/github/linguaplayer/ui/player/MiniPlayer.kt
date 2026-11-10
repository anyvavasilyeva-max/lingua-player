package io.github.linguaplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.LinearProgressIndicator
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

/**
 * Полоска «сейчас играет».
 *
 * [aboveNavigationBar] = true — под ней нижнее меню приложения (оно само отступает от системных
 * кнопок). false — полоска внизу экрана: добавляется отступ под системные кнопки Android
 * (◁ ○ □ или жестовая полоска), а фон продолжается под ними, чтобы кнопки не перекрывали текст.
 */
@Composable
fun MiniPlayer(player: PlayerEngine, onOpen: () -> Unit, aboveNavigationBar: Boolean) {
    val st by player.state.collectAsStateWithLifecycle()
    val item = st.current ?: return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(if (aboveNavigationBar) Modifier else Modifier.navigationBarsPadding()) {
            // Тонкая полоса позиции в очереди.
            LinearProgressIndicator(
                progress = { (st.index + 1).toFloat() / st.queue.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier.heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.source, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.translation,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${st.title} · ${st.index + 1}/${st.queue.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledIconButton(onClick = player::togglePlay, modifier = Modifier.size(52.dp)) {
                    Icon(
                        if (st.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        stringResource(if (st.isPlaying) R.string.player_pause else R.string.player_play),
                        Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = player::next, enabled = player.hasNext(st), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Outlined.SkipNext, stringResource(R.string.player_next), Modifier.size(30.dp))
                }
            }
        }
    }
}
