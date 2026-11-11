package io.github.linguaplayer.voice

import io.github.linguaplayer.util.TextKeys
import java.text.Normalizer

/** Оценка ответа голосом. */
enum class Verdict { CORRECT, ALMOST, WRONG }

data class VoiceResult(
    val verdict: Verdict,
    /** Что распознано (лучший вариант). */
    val heard: String,
    /** С каким вариантом правильного ответа совпало лучше всего. */
    val expected: String,
    /** Похожесть 0..1. */
    val similarity: Double,
)

/**
 * Сравнение распознанной речи с правильным ответом.
 *
 * - Ответ может содержать несколько вариантов: «Груша; живот; лодка» — засчитывается любой.
 * - Пояснения в скобках не нужно произносить: «Ты поел? (приветствие)» → «ты поел».
 * - Регистр, знаки препинания и лишние пробелы не важны.
 * - Для корейского слоги раскладываются на буквы (чамо): 포크 и 포그 отличаются одной буквой
 *   из шести — это «почти», а не «неверно». Для других языков сравнение по буквам.
 * - Распознаватель возвращает несколько гипотез — берётся лучшая.
 */
object AnswerMatcher {
    const val ALMOST_THRESHOLD = 0.75

    private val brackets = Regex("[(\\[{（【].*?[)\\]}）】]")
    private val punctuation = Regex("[\\p{P}\\p{S}]")
    private val spaces = Regex("\\s+")

    /** Варианты правильного ответа: разделители «;», «/», «|», а также «,» между вариантами. */
    fun variants(answer: String): List<String> {
        val cleaned = answer.replace(brackets, " ")
        val parts = cleaned.split(';', '/', '|', '；').flatMap { it.split(',', '，') }
        val list = (parts + cleaned).map { normalize(it) }.filter { it.isNotEmpty() }
        return list.distinct()
    }

    fun normalize(text: String): String =
        TextKeys.key(text.replace(brackets, " ")).replace(punctuation, " ").replace(spaces, " ").trim()
            .replace('ё', 'е')

    /** Раскладывает корейские слоги на чамо (Unicode NFD), остальное оставляет как есть. */
    private fun units(text: String): String = Normalizer.normalize(text.replace(" ", ""), Normalizer.Form.NFD)

    fun similarity(a: String, b: String): Double {
        val x = units(a)
        val y = units(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val d = levenshtein(x, y)
        return 1.0 - d.toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun evaluate(hypotheses: List<String>, answer: String): VoiceResult {
        val expected = variants(answer)
        var best = VoiceResult(Verdict.WRONG, hypotheses.firstOrNull().orEmpty(), expected.firstOrNull().orEmpty(), 0.0)
        for (h in hypotheses) {
            val heard = normalize(h)
            if (heard.isEmpty()) continue
            for (e in expected) {
                val sim = if (heard == e || heard.replace(" ", "") == e.replace(" ", "")) 1.0 else similarity(heard, e)
                if (sim > best.similarity) best = VoiceResult(Verdict.WRONG, h.trim(), e, sim)
            }
        }
        val verdict = when {
            best.similarity >= 0.999 -> Verdict.CORRECT
            best.similarity >= ALMOST_THRESHOLD -> Verdict.ALMOST
            else -> Verdict.WRONG
        }
        return best.copy(verdict = verdict)
    }
}
