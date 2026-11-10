package io.github.linguaplayer.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOn
import androidx.compose.material.icons.outlined.RepeatOneOn
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.ShuffleOn
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.IconToggleButton
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import io.github.linguaplayer.player.PlayOrder
import io.github.linguaplayer.player.QueueItem
import io.github.linguaplayer.player.RepeatMode
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.R
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.languageDisplayName
import io.github.linguaplayer.player.Direction
import io.github.linguaplayer.player.Part
import io.github.linguaplayer.player.PlayerError
import io.github.linguaplayer.ui.components.EmptyState
import io.github.linguaplayer.ui.components.appContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, onOpenVoices: () -> Unit, onCloseToHome: () -> Unit = onBack) {
    val c = appContainer()
    val vm: PlayerViewModel = viewModel { PlayerViewModel(c) }
    val st by vm.state.collectAsStateWithLifecycle()
    val dontRemember by vm.dontRemember.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hiddenAllMsg = stringResource(R.string.player_all_hidden)
    val shownAllMsg = stringResource(R.string.player_all_shown)
    val item = st.current
    val sourceName = item?.let { languageDisplayName(it.sourceLang) } ?: stringResource(R.string.ps_studied_language)
    val targetName = item?.let { languageDisplayName(it.targetLang) } ?: stringResource(R.string.ps_translation_language)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.action_back)) }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(st.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Tune, stringResource(R.string.player_settings)) }
                },
            )
        },
    ) { padding ->
        if (!st.hasQueue) {
            Column(Modifier.padding(padding)) {
                if (st.title.isNotEmpty()) {
                    val filter = when (st.settings.typeFilter) {
                        TypeFilter.WORDS -> stringResource(R.string.filter_words)
                        TypeFilter.PHRASES -> stringResource(R.string.filter_phrases)
                        TypeFilter.ALL -> ""
                    }
                    EmptyState(
                        if (filter.isNotEmpty()) stringResource(R.string.player_queue_filtered, filter)
                        else stringResource(R.string.player_nothing_to_play),
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = { showSettings = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp)) {
                        Text(stringResource(R.string.player_settings))
                    }
                } else {
                    EmptyState(stringResource(R.string.player_empty))
                }
            }
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.player_position, st.index + 1, st.queue.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                item?.let {
                    FlashCard(
                        item = it,
                        speaking = st.speaking,
                        hideAnswer = st.settings.hideAnswer,
                        sourceName = sourceName,
                        targetName = targetName + if (!st.settings.translationEnabled) " · " + stringResource(R.string.player_no_translation) else "",
                        onNext = { if (vm.hasNext(st)) vm.next() },
                        onPrevious = { if (vm.hasPrevious(st)) vm.previous() },
                        onSwipeDown = onCloseToHome,
                        onSetAllHidden = { hide ->
                            vm.updateSettings { it.copy(hideAnswer = hide) }
                            // Toast не перекрывает кнопки плеера (в отличие от snackbar).
                            Toast.makeText(context, if (hide) hiddenAllMsg else shownAllMsg, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsSummary(st.settings, item?.sourceFirst ?: true, sourceName, targetName) { showSettings = true }

                st.error?.let { ErrorCard(it, onOpenVoices) }
                if (st.ended && st.error == null) {
                    Text(stringResource(R.string.player_ended), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                }

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val shuffle = st.settings.order == PlayOrder.SHUFFLE
                    IconToggleButton(checked = shuffle, onCheckedChange = { vm.toggleShuffle() }) {
                        Icon(
                            if (shuffle) Icons.Outlined.ShuffleOn else Icons.Outlined.Shuffle,
                            stringResource(R.string.player_shuffle),
                            tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = vm::previous, enabled = vm.hasPrevious(st), modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Outlined.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(34.dp))
                    }
                    FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(72.dp)) {
                        Icon(
                            if (st.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            stringResource(if (st.isPlaying) R.string.player_pause else R.string.player_play),
                            Modifier.size(40.dp),
                        )
                    }
                    IconButton(onClick = vm::next, enabled = vm.hasNext(st), modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Outlined.SkipNext, stringResource(R.string.player_next), Modifier.size(34.dp))
                    }
                    val mode = st.settings.repeatMode
                    IconButton(onClick = vm::cycleRepeat) {
                        Icon(
                            when (mode) {
                                RepeatMode.OFF -> Icons.Outlined.Repeat
                                RepeatMode.ALL -> Icons.Outlined.RepeatOn
                                RepeatMode.ONE -> Icons.Outlined.RepeatOneOn
                            },
                            stringResource(
                                when (mode) {
                                    RepeatMode.OFF -> R.string.player_repeat_off
                                    RepeatMode.ALL -> R.string.player_repeat_all
                                    RepeatMode.ONE -> R.string.player_repeat_one
                                },
                            ),
                            tint = if (mode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (dontRemember) {
                    Button(
                        onClick = vm::toggleDontRemember,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Help, null)
                        Text(stringResource(R.string.player_dont_remember_on), Modifier.padding(start = 8.dp))
                    }
                } else {
                    OutlinedButton(onClick = vm::toggleDontRemember) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, null)
                        Text(stringResource(R.string.player_dont_remember), Modifier.padding(start = 8.dp))
                    }
                }
                // Все подсказки — под «Не помню», чтобы не загромождать карточку.
                Text(
                    stringResource(R.string.player_card_hints),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showSettings) {
        PlaybackSettingsSheet(
            settings = st.settings,
            sourceName = sourceName,
            targetName = targetName,
            onChange = vm::updateSettings,
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * Карточка записи. Свайп влево/вправо — следующая/предыдущая (без прослушивания, если плеер на паузе).
 * Если включено «Скрывать ответ», вторая часть (перевод, а при обратном направлении — слово)
 * скрыта до нажатия на 👁 или на карточку; на новой карточке снова скрыта.
 */
@Composable
private fun FlashCard(
    item: QueueItem,
    speaking: Part?,
    hideAnswer: Boolean,
    sourceName: String,
    targetName: String,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSwipeDown: () -> Unit,
    onSetAllHidden: (Boolean) -> Unit,
) {
    // Карточка следует за пальцем при свайпе вниз (визуальный отклик); отпустили раньше порога — возвращается.
    var dragY by remember(item.entryId) { mutableFloatStateOf(0f) }
    val offsetY by animateFloatAsState(dragY, label = "cardDragY")
    // Короткое нажатие меняет только эту карточку; на следующей действует общий режим [hideAnswer].
    var override by remember(item.entryId, item.sourceFirst, hideAnswer) { mutableStateOf<Boolean?>(null) }
    val answerHidden = override ?: hideAnswer
    val swipeThreshold = with(LocalDensity.current) { 80.dp.toPx() }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                translationY = offsetY
                alpha = 1f - (offsetY / (size.height * 2f)).coerceIn(0f, 0.5f)
            }
            .pointerInput(item.entryId) {
                // Влево/вправо — следующая/предыдущая; вниз — закрыть плеер и выйти на главную.
                // Направление определяется по преобладающей оси движения.
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragCancel = { dragY = 0f },
                    onDragEnd = {
                        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                        when {
                            horizontal && dx < -swipeThreshold -> onNext()
                            horizontal && dx > swipeThreshold -> onPrevious()
                            !horizontal && dy > swipeThreshold -> onSwipeDown()
                        }
                        dragY = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    dx += amount.x
                    dy += amount.y
                    if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) dragY = dy.coerceAtLeast(0f) * 0.6f
                }
            }
            .clickable { override = !answerHidden },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Первым показывается то, что звучит первым.
                val first = if (item.sourceFirst) Triple(sourceName, item.source, Part.SOURCE) else Triple(targetName, item.translation, Part.TRANSLATION)
                val second = if (item.sourceFirst) Triple(targetName, item.translation, Part.TRANSLATION) else Triple(sourceName, item.source, Part.SOURCE)
                SpokenText(first.first, first.second, speaking == first.third, big = first.third == Part.SOURCE)
                Spacer(Modifier.height(16.dp))
                if (answerHidden) {
                    Text(second.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• • •", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SpokenText(second.first, second.second, speaking == second.third, big = second.third == Part.SOURCE)
                }
            }
            EyeButton(
                hidden = answerHidden,
                onTap = { override = !answerHidden },
                // Удержание: «скрыть» → скрыть на всех карточках, «показать» → открыть на всех.
                onHold = { onSetAllHidden(!answerHidden) },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
    }
}

/** 👁: значок — текущее состояние ответа; нажатие — текущая карточка, удержание — все карточки. */
@Composable
private fun EyeButton(hidden: Boolean, onTap: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(if (hidden) R.string.player_show_answer else R.string.player_hide_answer)
    val holdLabel = stringResource(if (hidden) R.string.player_show_all else R.string.player_hide_all)
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClickLabel = description,
                onLongClickLabel = holdLabel,
                onLongClick = onHold,
                onClick = onTap,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Значок показывает текущее состояние: перечёркнутый глаз — ответ скрыт, открытый — виден.
        Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
    }
}

/** Текст с подписью языка; подсвечивается, пока звучит. */
@Composable
private fun SpokenText(label: String, text: String, active: Boolean, big: Boolean) {
    val color by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        label = "spoken",
    )
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text,
        style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
        fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SettingsSummary(
    s: io.github.linguaplayer.player.PlaybackSettings,
    sourceFirst: Boolean,
    sourceName: String,
    targetName: String,
    onClick: () -> Unit,
) {
    val dir = when (s.direction) {
        Direction.RANDOM -> stringResource(R.string.ps_dir_random)
        else -> if (sourceFirst) "$sourceName → $targetName" else "$targetName → $sourceName"
    }
    val parts = listOfNotNull(
        dir,
        stringResource(R.string.ps_summary_pause, s.pauseSeconds),
        stringResource(R.string.ps_repeats_value, s.repeats),
        if (s.sourceRate != 1f || s.targetRate != 1f) "${rateLabel(s.sourceRate)} / ${rateLabel(s.targetRate)}" else null,
    )
    AssistChip(onClick = onClick, label = { Text(parts.joinToString(" · "), maxLines = 1) })
}

@Composable
private fun ErrorCard(error: PlayerError, onOpenVoices: () -> Unit) {
    val text = when (error) {
        PlayerError.EngineUnavailable -> stringResource(R.string.player_err_engine)
        is PlayerError.LanguageUnavailable -> stringResource(R.string.player_err_language, languageDisplayName(error.lang))
        PlayerError.RepeatedFailures -> stringResource(R.string.player_err_failures)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, null)
                Text(text, Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onOpenVoices, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.player_open_voices))
            }
        }
    }
}
