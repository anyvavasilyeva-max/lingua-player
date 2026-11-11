package io.github.linguaplayer.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerMatcherTest {
    private fun verdict(heard: String, answer: String) = AnswerMatcher.evaluate(listOf(heard), answer).verdict

    @Test
    fun exactIgnoringCasePunctuationSpaces() {
        assertEquals(Verdict.CORRECT, verdict("포크", "포크"))
        assertEquals(Verdict.CORRECT, verdict("вилка", "Вилка"))
        assertEquals(Verdict.CORRECT, verdict("Что мне следует сделать", "Что мне следует сделать?"))
        assertEquals(Verdict.CORRECT, verdict("설거지를하다", "설거지를 하다")) // пробелы не важны
        assertEquals(Verdict.CORRECT, verdict("ёлка", "Елка"))
    }

    @Test
    fun anyOfSeveralTranslations() {
        assertEquals(Verdict.CORRECT, verdict("лодка", "Груша; живот; лодка"))
        assertEquals(Verdict.CORRECT, verdict("котелок", "Кастрюля, котелок"))
        assertEquals(Verdict.CORRECT, verdict("ты поел", "Ты поел? (приветствие)"))
    }

    @Test
    fun koreanNearMissIsAlmost() {
        // 포크 → 포그: одна буква (ㅋ→ㄱ) из четырёх чамо.
        val r = AnswerMatcher.evaluate(listOf("포그"), "포크")
        assertEquals(Verdict.ALMOST, r.verdict)
        assertEquals(Verdict.WRONG, verdict("학교", "포크"))
    }

    @Test
    fun russianTypoIsAlmostOtherWordIsWrong() {
        assertEquals(Verdict.ALMOST, verdict("вилко", "Вилка"))
        assertEquals(Verdict.WRONG, verdict("ложка", "Вилка"))
    }

    @Test
    fun bestOfSeveralHypotheses() {
        val r = AnswerMatcher.evaluate(listOf("пилка", "вилка", "билка"), "Вилка")
        assertEquals(Verdict.CORRECT, r.verdict)
        assertEquals("вилка", r.heard)
    }

    @Test
    fun emptyOrSilence() {
        assertEquals(Verdict.WRONG, AnswerMatcher.evaluate(emptyList(), "Вилка").verdict)
        assertEquals(Verdict.WRONG, verdict("   ", "Вилка"))
    }
}
