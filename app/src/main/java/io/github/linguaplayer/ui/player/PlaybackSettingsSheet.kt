package io.github.linguaplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.linguaplayer.R
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.player.Direction
import io.github.linguaplayer.player.PlayOrder
import io.github.linguaplayer.player.PlaybackSettings
import io.github.linguaplayer.player.RepeatMode

fun rateLabel(rate: Float): String {
    val text = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString().replace('.', ',')
    return "$text×"
}

/** Панель всех параметров воспроизведения. [sourceName]/[targetName] — названия языков. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaybackSettingsSheet(
    settings: PlaybackSettings,
    sourceName: String,
    targetName: String,
    onChange: ((PlaybackSettings) -> PlaybackSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.player_settings), style = MaterialTheme.typography.titleLarge)

            Section(stringResource(R.string.ps_direction))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(settings.direction == Direction.SOURCE_TO_TARGET, "$sourceName → $targetName") {
                    onChange { it.copy(direction = Direction.SOURCE_TO_TARGET) }
                }
                Chip(settings.direction == Direction.TARGET_TO_SOURCE, "$targetName → $sourceName") {
                    onChange { it.copy(direction = Direction.TARGET_TO_SOURCE) }
                }
                Chip(settings.direction == Direction.RANDOM, stringResource(R.string.ps_dir_random)) {
                    onChange { it.copy(direction = Direction.RANDOM) }
                }
            }

            Section(stringResource(R.string.ps_entries))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TypeFilter.ALL to R.string.filter_all, TypeFilter.WORDS to R.string.filter_words, TypeFilter.PHRASES to R.string.filter_phrases)
                    .forEach { (t, label) -> Chip(settings.typeFilter == t, stringResource(label)) { onChange { it.copy(typeFilter = t) } } }
            }

            Section(stringResource(R.string.ps_order))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(settings.order == PlayOrder.SEQUENTIAL, stringResource(R.string.ps_order_seq)) { onChange { it.copy(order = PlayOrder.SEQUENTIAL) } }
                Chip(settings.order == PlayOrder.SHUFFLE, stringResource(R.string.ps_order_shuffle)) { onChange { it.copy(order = PlayOrder.SHUFFLE) } }
            }

            Section(stringResource(R.string.ps_repeat_mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    RepeatMode.OFF to R.string.ps_repeat_off,
                    RepeatMode.ALL to R.string.player_repeat_all,
                    RepeatMode.ONE to R.string.player_repeat_one,
                ).forEach { (m, label) -> Chip(settings.repeatMode == m, stringResource(label)) { onChange { it.copy(repeatMode = m) } } }
            }

            Section(stringResource(R.string.ps_pause))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackSettings.PAUSES.forEach { p ->
                    Chip(settings.pauseSeconds == p, stringResource(R.string.ps_seconds, p)) { onChange { it.copy(pauseSeconds = p) } }
                }
            }

            Section(stringResource(R.string.ps_repeats, sourceName.lowercase()))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackSettings.REPEATS.forEach { r ->
                    Chip(settings.repeats == r, stringResource(R.string.ps_repeats_value, r)) { onChange { it.copy(repeats = r) } }
                }
            }

            SwitchRow(
                stringResource(R.string.ps_slow_repeat),
                stringResource(R.string.ps_slow_repeat_desc),
                settings.slowRepeat,
            ) { v -> onChange { it.copy(slowRepeat = v) } }

            Section(stringResource(R.string.ps_rate_source, sourceName))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackSettings.RATES.forEach { r ->
                    Chip(settings.sourceRate == r, rateLabel(r)) { onChange { it.copy(sourceRate = r) } }
                }
            }
            Section(stringResource(R.string.ps_rate_target, targetName))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackSettings.RATES.forEach { r ->
                    Chip(settings.targetRate == r, rateLabel(r)) { onChange { it.copy(targetRate = r) } }
                }
            }

            SwitchRow(
                stringResource(R.string.ps_translation),
                stringResource(R.string.ps_translation_desc),
                settings.translationEnabled,
            ) { v -> onChange { it.copy(translationEnabled = v) } }
            SwitchRow(
                stringResource(R.string.ps_hide_answer),
                stringResource(R.string.ps_hide_answer_desc),
                settings.hideAnswer,
            ) { v -> onChange { it.copy(hideAnswer = v) } }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onToggle).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
