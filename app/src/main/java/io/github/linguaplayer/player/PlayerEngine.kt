package io.github.linguaplayer.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed interface PlayerError {
    /** Синтезатор речи не запустился. */
    data object EngineUnavailable : PlayerError
    /** Для языка нет голоса (не установлен или не поддерживается). */
    data class LanguageUnavailable(val lang: String) : PlayerError
    /** Несколько фраз подряд не удалось произнести. */
    data object RepeatedFailures : PlayerError
}

data class PlayerState(
    val title: String = "",
    val playlistId: Long? = null,
    val queue: List<QueueItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    /** Что звучит прямо сейчас; null — пауза между фразами или остановка. */
    val speaking: Part? = null,
    /** Очередь доиграна до конца. */
    val ended: Boolean = false,
    val error: PlayerError? = null,
    val settings: PlaybackSettings = PlaybackSettings(),
) {
    val current: QueueItem? get() = queue.getOrNull(index)
    val hasQueue: Boolean get() = queue.isNotEmpty()
}

/**
 * Очередь и состояние воспроизведения. Не зависит от Android: речь — через [SpeechEngine].
 *
 * Модель: одна корутина [job] проигрывает записи одну за другой по плану [SpeechPlanner].
 * Любая команда (пауза, следующая, предыдущая) сначала полностью останавливает текущую
 * корутину (cancel + join, stop движка), и только потом запускает новую — поэтому две фразы
 * не могут звучать одновременно. После паузы запись начинается заново с первого шага.
 *
 * Все методы вызываются из одного потока (главного) — так же, как MediaSession на этапе 4.
 */
class PlayerEngine(
    private val scope: CoroutineScope,
    private val speech: SpeechEngine,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var source: PlaybackSource? = null
    private var job: Job? = null
    private var consecutiveFailures = 0

    /** Загружает новую очередь. [startEntryId] — с какой записи начать. */
    fun load(src: PlaybackSource, startEntryId: Long? = null, autoplay: Boolean = true) {
        source = src
        val s = _state.value.settings
        val queue = QueueBuilder.build(src, s, random, keepFirst = startEntryId)
        val index = startEntryId?.let { id -> queue.indexOfFirst { it.entryId == id } }?.takeIf { it >= 0 } ?: 0
        restart(autoplay) {
            it.copy(title = src.title, playlistId = src.playlistId, queue = queue, index = index, ended = false, error = null)
        }
    }

    /** Источник текущей очереди (для сохранения и восстановления после перезапуска). */
    fun currentSource(): PlaybackSource? = source

    /**
     * Восстанавливает сохранённую очередь без воспроизведения: [order] — пары (id записи,
     * «сначала изучаемый язык»). Записи, которых больше нет в [src], пропускаются.
     */
    fun restore(src: PlaybackSource, order: List<Pair<Long, Boolean>>, index: Int) {
        val byId = src.entries.associateBy { it.id }
        val queue = order.mapNotNull { (id, sourceFirst) ->
            val e = byId[id] ?: return@mapNotNull null
            val pair = src.pairs[e.pairId] ?: return@mapNotNull null
            QueueItem(e.id, e.pairId, e.source, e.translation, e.type, pair.sourceLang, pair.targetLang, sourceFirst)
        }
        if (queue.isEmpty()) return
        source = src
        restart(false) {
            it.copy(title = src.title, playlistId = src.playlistId, queue = queue, index = index.coerceIn(0, queue.lastIndex), ended = false, error = null)
        }
    }

    /** Новые настройки. Если изменились направление/тип/порядок — очередь перестраивается с сохранением текущей записи. */
    fun updateSettings(new: PlaybackSettings) {
        val old = _state.value.settings
        if (old == new) return
        val src = source
        if (src == null || old.queueKey() == new.queueKey()) {
            // Паузы, повторы, скорость — применятся со следующего шага без перезапуска.
            _state.update { it.copy(settings = new) }
            return
        }
        val currentId = _state.value.current?.entryId
        val queue = QueueBuilder.build(src, new, random, keepFirst = currentId)
        val index = queue.indexOfFirst { it.entryId == currentId }.takeIf { it >= 0 } ?: 0
        val wasPlaying = _state.value.isPlaying
        restart(wasPlaying) { it.copy(settings = new, queue = queue, index = index, ended = false) }
    }

    fun play() {
        val st = _state.value
        if (!st.hasQueue) return
        restart(true) { if (it.ended) it.copy(index = 0, ended = false, error = null) else it.copy(error = null) }
    }

    /** Слушатели паузы, запрошенной извне (кнопка, уведомление, гарнитура или система). */
    private val pauseListeners = mutableListOf<() -> Unit>()

    fun addPauseListener(listener: () -> Unit) { pauseListeners += listener }

    fun pause() {
        pauseListeners.forEach { it() }
        restart(false) { it }
    }

    fun togglePlay() = if (_state.value.isPlaying) pause() else play()

    /** Есть ли следующая запись (при «зациклить список» — всегда). */
    fun hasNext(st: PlayerState = _state.value) =
        st.hasQueue && (st.index + 1 < st.queue.size || st.settings.repeatMode == RepeatMode.ALL)

    fun hasPrevious(st: PlayerState = _state.value) =
        st.hasQueue && (st.index > 0 || st.settings.repeatMode == RepeatMode.ALL)

    fun next() {
        val st = _state.value
        if (!hasNext(st)) return
        restart(st.isPlaying) { it.copy(index = (it.index + 1) % it.queue.size, ended = false) }
    }

    fun previous() {
        val st = _state.value
        if (!st.hasQueue) return
        val target = when {
            st.index > 0 -> st.index - 1
            st.settings.repeatMode == RepeatMode.ALL -> st.queue.lastIndex
            else -> 0
        }
        restart(st.isPlaying) { it.copy(index = target, ended = false) }
    }

    fun seekTo(index: Int) {
        val st = _state.value
        if (index !in st.queue.indices) return
        restart(st.isPlaying) { it.copy(index = index, ended = false) }
    }

    /** Полная остановка с очисткой очереди. */
    fun clear() {
        source = null
        restart(false) { PlayerState(settings = it.settings) }
    }

    /**
     * Останавливает текущее воспроизведение, применяет [change] к состоянию и, если [playing],
     * запускает новую корутину проигрывания.
     */
    private fun restart(playing: Boolean, change: (PlayerState) -> PlayerState) {
        val old = job
        // Отмена — синхронно и до остановки движка: если движок успеет вернуть результат
        // прерванной фразы, старая корутина уже отменена и ничего не изменит.
        old?.cancel()
        speech.stop()
        val next = change(_state.value)
        val nowPlaying = playing && next.hasQueue
        _state.value = next.copy(isPlaying = nowPlaying, speaking = null)
        consecutiveFailures = 0
        job = if (nowPlaying) {
            scope.launch {
                old?.join() // дождаться полного завершения старой корутины
                runLoop()
            }
        } else {
            null
        }
    }

    private suspend fun runLoop() {
        if (!speech.awaitReady()) {
            stopWith(PlayerError.EngineUnavailable)
            return
        }
        while (true) {
            val st = _state.value
            val item = st.current ?: break
            for (step in SpeechPlanner.steps(item, st.settings)) {
                when (step) {
                    is Step.Silence -> {
                        _state.update { it.copy(speaking = null) }
                        delay(step.ms)
                    }
                    is Step.Speak -> {
                        _state.update { it.copy(speaking = step.part) }
                        // Скорость берётся из актуальных настроек — её можно менять на ходу.
                        val rate = SpeechPlanner.rate(step, _state.value.settings)
                        when (val r = speech.speak(step.text, step.lang, rate)) {
                            SpeakResult.Done -> consecutiveFailures = 0
                            is SpeakResult.LanguageUnavailable -> {
                                stopWith(PlayerError.LanguageUnavailable(r.lang))
                                return
                            }
                            is SpeakResult.Error, SpeakResult.Timeout -> {
                                if (++consecutiveFailures >= MAX_FAILURES) {
                                    stopWith(PlayerError.RepeatedFailures)
                                    return
                                }
                            }
                        }
                    }
                }
            }
            val now = _state.value
            when {
                now.settings.repeatMode == RepeatMode.ONE -> _state.update { it.copy(speaking = null) }
                now.index + 1 < now.queue.size -> _state.update { it.copy(index = it.index + 1, speaking = null) }
                now.settings.repeatMode == RepeatMode.ALL -> _state.update { it.copy(index = 0, speaking = null) }
                else -> {
                    _state.update { it.copy(isPlaying = false, speaking = null, ended = true) }
                    break
                }
            }
        }
    }

    private fun stopWith(error: PlayerError) {
        _state.update { it.copy(isPlaying = false, speaking = null, error = error) }
    }

    companion object {
        const val MAX_FAILURES = 3
    }
}
