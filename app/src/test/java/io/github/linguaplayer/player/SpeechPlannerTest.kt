package io.github.linguaplayer.player

import io.github.linguaplayer.player.Step.Silence.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPlannerTest {
    private val item = QueueItem(1, 1, "포크", "Вилка", io.github.linguaplayer.data.db.EntryType.WORD, "ko-KR", "ru-RU", sourceFirst = true)

    private fun describe(steps: List<Step>, s: PlaybackSettings) = steps.map {
        when (it) {
            is Step.Speak -> "${it.text}@${it.lang}x${SpeechPlanner.rate(it, s)}"
            is Step.Silence -> "${it.kind}:${it.ms}"
        }
    }

    @Test
    fun sourceFirstWithRepeatsAndPause() {
        val s = PlaybackSettings(repeats = 2, pauseSeconds = 5, sourceRate = 0.75f, targetRate = 1.25f)
        assertEquals(
            listOf("포크@ko-KRx0.75", "REPEAT_GAP:800", "포크@ko-KRx0.75", "USER_PAUSE:5000", "Вилка@ru-RUx1.25", "ITEM_GAP:1200"),
            describe(SpeechPlanner.steps(item, s), s),
        )
    }

    @Test
    fun translationFirstPauseWorksInBothDirections() {
        val s = PlaybackSettings(repeats = 3, pauseSeconds = 2)
        assertEquals(
            listOf("Вилка@ru-RUx1.0", "USER_PAUSE:2000", "포크@ko-KRx1.0", "REPEAT_GAP:800", "포크@ko-KRx1.0", "REPEAT_GAP:800", "포크@ko-KRx1.0", "ITEM_GAP:1200"),
            describe(SpeechPlanner.steps(item.copy(sourceFirst = false), s), s),
        )
    }

    @Test
    fun zeroPauseHasNoSilenceStep() {
        val s = PlaybackSettings(pauseSeconds = 0)
        val steps = SpeechPlanner.steps(item, s)
        assertEquals(listOf("포크@ko-KRx1.0", "Вилка@ru-RUx1.0", "ITEM_GAP:1200"), describe(steps, s))
        assertEquals(0, steps.count { it is Step.Silence && it.kind == Kind.USER_PAUSE })
    }

    @Test
    fun noTranslationModeSpeaksOnlySourceInAnyDirection() {
        val s = PlaybackSettings(translationEnabled = false, repeats = 2)
        val expected = listOf("포크@ko-KRx1.0", "REPEAT_GAP:800", "포크@ko-KRx1.0", "ITEM_GAP:1200")
        assertEquals(expected, describe(SpeechPlanner.steps(item, s), s))
        assertEquals(expected, describe(SpeechPlanner.steps(item.copy(sourceFirst = false), s), s))
    }

    private fun sourceRates(s: PlaybackSettings) =
        SpeechPlanner.steps(item, s).filterIsInstance<Step.Speak>().filter { it.part == Part.SOURCE }.map { SpeechPlanner.rate(it, s) }

    @Test
    fun slowRepeatSecondTimeOneStepSlower() {
        // 2 раза на 1×: 1× · 0,75×
        assertEquals(listOf(1f, 0.75f), sourceRates(PlaybackSettings(repeats = 2, slowRepeat = true)))
        // 3 раза на 1×: 1× · 0,75× · 1×
        assertEquals(listOf(1f, 0.75f, 1f), sourceRates(PlaybackSettings(repeats = 3, slowRepeat = true)))
        // 2× → 1,5× (на одну ступень)
        assertEquals(listOf(2f, 1.5f), sourceRates(PlaybackSettings(repeats = 2, slowRepeat = true, sourceRate = 2f)))
        // Самая медленная ступень не опускается ниже.
        assertEquals(listOf(0.5f, 0.5f), sourceRates(PlaybackSettings(repeats = 2, slowRepeat = true, sourceRate = 0.5f)))
        // Без замедления все повторы одинаковые; перевод не замедляется.
        assertEquals(listOf(1f, 1f, 1f), sourceRates(PlaybackSettings(repeats = 3)))
        val s = PlaybackSettings(repeats = 2, slowRepeat = true)
        val translation = SpeechPlanner.steps(item, s).filterIsInstance<Step.Speak>().single { it.part == Part.TRANSLATION }
        assertEquals(1f, SpeechPlanner.rate(translation, s))
    }

    @Test
    fun slowerSteps() {
        assertEquals(0.75f, PlaybackSettings.slower(1f))
        assertEquals(1f, PlaybackSettings.slower(1.25f))
        assertEquals(1.5f, PlaybackSettings.slower(2f))
        assertEquals(0.5f, PlaybackSettings.slower(0.5f))
    }
}
