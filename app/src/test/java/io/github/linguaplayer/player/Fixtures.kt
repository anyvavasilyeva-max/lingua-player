package io.github.linguaplayer.player

import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.LanguagePairEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

val KO_RU = LanguagePairEntity(id = 1, sourceLang = "ko-KR", targetLang = "ru-RU", createdAt = 0)

fun entry(id: Long, source: String, translation: String, type: EntryType = EntryType.WORD, pairId: Long = 1) =
    EntryEntity(id, pairId, source, translation, type, source, translation.lowercase(), 0, 0)

val SAMPLE = listOf(
    entry(1, "포크", "Вилка"),
    entry(2, "숟가락", "Ложка"),
    entry(3, "설거지를 하다", "Мыть посуду", EntryType.PHRASE),
    entry(4, "젓가락", "Палочки"),
    entry(5, "어떻게 해야 할까요?", "Что мне делать?", EntryType.PHRASE),
)

fun source(entries: List<EntryEntity> = SAMPLE) = PlaybackSource("Кухня", entries, mapOf(1L to KO_RU))

/**
 * Поддельный синтезатор: каждая фраза «звучит» [durationMs] виртуального времени.
 * Записывает начало и конец каждой фразы — по ним тесты проверяют отсутствие наложений.
 */
class FakeSpeech(private val scope: TestScope, private val durationMs: Long = 1000) : SpeechEngine {
    data class Utterance(val text: String, val lang: String, val rate: Float, val start: Long, var end: Long? = null)

    override val status = MutableStateFlow(EngineStatus.READY)
    val log = mutableListOf<Utterance>()
    var unavailable = setOf<String>()
    var failAll = false
    var ready = true
    var stops = 0

    override suspend fun awaitReady() = ready

    override suspend fun speak(text: String, lang: String, rate: Float): SpeakResult {
        if (lang in unavailable) return SpeakResult.LanguageUnavailable(lang)
        if (failAll) return SpeakResult.Error(-1)
        val u = Utterance(text, lang, rate, scope.currentTime)
        log += u
        delay(durationMs) // при отмене корутины фраза обрывается, end не заполняется
        u.end = scope.currentTime
        return SpeakResult.Done
    }

    override fun stop() { stops++ }

    val texts get() = log.map { it.text }
}
