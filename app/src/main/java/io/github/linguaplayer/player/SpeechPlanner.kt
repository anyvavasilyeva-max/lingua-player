package io.github.linguaplayer.player

/** Какая часть записи звучит. */
enum class Part { SOURCE, TRANSLATION }

sealed interface Step {
    /**
     * [slowed] — повтор с замедлением: скорость на ступень ниже выбранной.
     * Сама скорость вычисляется в момент произнесения ([SpeechPlanner.rate]), поэтому
     * её изменение в настройках применяется сразу, даже посреди записи.
     */
    data class Speak(val text: String, val lang: String, val part: Part, val slowed: Boolean = false) : Step
    data class Silence(val ms: Long, val kind: Kind) : Step {
        enum class Kind {
            /** Пауза между словом и переводом, выбранная пользователем. */
            USER_PAUSE,
            /** Короткий интервал между повторами одного текста. */
            REPEAT_GAP,
            /** Интервал после записи перед следующей. */
            ITEM_GAP,
        }
    }
}

/**
 * Точная последовательность звучания одной записи. Шаги выполняются строго по очереди:
 * следующий начинается только после окончания предыдущего, поэтому речь не накладывается.
 *
 * Изучаемый язык → перевод (повторы = 2, пауза = 2 с):
 *   포크 · 0,8 с · 포크 · 2 с · Вилка · 1,2 с
 * Перевод → изучаемый язык:
 *   Вилка · 2 с · 포크 · 0,8 с · 포크 · 1,2 с
 * Без перевода:
 *   포크 · 0,8 с · 포크 · 1,2 с
 *
 * Повторы относятся только к тексту на изучаемом языке; перевод звучит один раз.
 *
 * Повтор с замедлением (slowRepeat): чётные повторы звучат на ступень медленнее
 * (ступени 0,5 · 0,75 · 1 · 1,25 · 1,5 · 2). 2 раза при 1×: 1× · 0,75×; 3 раза: 1× · 0,75× · 1×.
 */
object SpeechPlanner {
    const val REPEAT_GAP_MS = 800L
    const val ITEM_GAP_MS = 1200L

    /** Скорость фразы по текущим настройкам. */
    fun rate(step: Step.Speak, s: PlaybackSettings): Float {
        val base = if (step.part == Part.SOURCE) s.sourceRate else s.targetRate
        return if (step.slowed) PlaybackSettings.slower(base) else base
    }

    fun steps(item: QueueItem, s: PlaybackSettings): List<Step> {
        val steps = ArrayList<Step>()
        val repeats = s.repeats.coerceIn(1, 3)
        fun sourceBlock() {
            repeat(repeats) { i ->
                if (i > 0) steps += Step.Silence(REPEAT_GAP_MS, Step.Silence.Kind.REPEAT_GAP)
                steps += Step.Speak(item.source, item.sourceLang, Part.SOURCE, slowed = s.slowRepeat && i % 2 == 1)
            }
        }
        fun translation() {
            steps += Step.Speak(item.translation, item.targetLang, Part.TRANSLATION)
        }
        fun userPause() {
            if (s.pauseSeconds > 0) steps += Step.Silence(s.pauseSeconds * 1000L, Step.Silence.Kind.USER_PAUSE)
        }

        when {
            !s.translationEnabled -> sourceBlock()
            item.sourceFirst -> { sourceBlock(); userPause(); translation() }
            else -> { translation(); userPause(); sourceBlock() }
        }
        steps += Step.Silence(ITEM_GAP_MS, Step.Silence.Kind.ITEM_GAP)
        return steps
    }
}
