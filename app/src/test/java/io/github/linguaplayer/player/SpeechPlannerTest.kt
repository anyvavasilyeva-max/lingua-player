package io.github.linguaplayer.player

import io.github.linguaplayer.player.Step.Silence.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPlannerTest {
    private val item = QueueItem(1, "포크", "Вилка", io.github.linguaplayer.data.db.EntryType.WORD, "ko-KR", "ru-RU", sourceFirst = true)

    private fun describe(steps: List<Step>) = steps.map {
        when (it) {
            is Step.Speak -> "${it.text}@${it.lang}x${it.rate}"
            is Step.Silence -> "${it.kind}:${it.ms}"
        }
    }

    @Test
    fun sourceFirstWithRepeatsAndPause() {
        val s = PlaybackSettings(repeats = 2, pauseSeconds = 5, sourceRate = 0.75f, targetRate = 1.25f)
        assertEquals(
            listOf("포크@ko-KRx0.75", "REPEAT_GAP:800", "포크@ko-KRx0.75", "USER_PAUSE:5000", "Вилка@ru-RUx1.25", "ITEM_GAP:1200"),
            describe(SpeechPlanner.steps(item, s)),
        )
    }

    @Test
    fun translationFirstPauseWorksInBothDirections() {
        val s = PlaybackSettings(repeats = 3, pauseSeconds = 2)
        assertEquals(
            listOf("Вилка@ru-RUx1.0", "USER_PAUSE:2000", "포크@ko-KRx1.0", "REPEAT_GAP:800", "포크@ko-KRx1.0", "REPEAT_GAP:800", "포크@ko-KRx1.0", "ITEM_GAP:1200"),
            describe(SpeechPlanner.steps(item.copy(sourceFirst = false), s)),
        )
    }

    @Test
    fun zeroPauseHasNoSilenceStep() {
        val steps = SpeechPlanner.steps(item, PlaybackSettings(pauseSeconds = 0))
        assertEquals(listOf("포크@ko-KRx1.0", "Вилка@ru-RUx1.0", "ITEM_GAP:1200"), describe(steps))
        assertEquals(0, steps.count { it is Step.Silence && it.kind == Kind.USER_PAUSE })
    }

    @Test
    fun noTranslationModeSpeaksOnlySourceInAnyDirection() {
        val s = PlaybackSettings(translationEnabled = false, repeats = 2)
        val expected = listOf("포크@ko-KRx1.0", "REPEAT_GAP:800", "포크@ko-KRx1.0", "ITEM_GAP:1200")
        assertEquals(expected, describe(SpeechPlanner.steps(item, s)))
        assertEquals(expected, describe(SpeechPlanner.steps(item.copy(sourceFirst = false), s)))
    }
}
