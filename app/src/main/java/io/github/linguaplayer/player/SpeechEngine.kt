package io.github.linguaplayer.player

import kotlinx.coroutines.flow.StateFlow

enum class EngineStatus { NOT_STARTED, INITIALIZING, READY, ERROR }

/** Состояние голоса для языка на текущем движке. */
enum class LanguageStatus {
    /** Голос установлен и работает без интернета. */
    AVAILABLE_OFFLINE,
    /** Язык поддерживается, но установленного офлайн-голоса не найдено — нужен интернет. */
    AVAILABLE_NETWORK_ONLY,
    /** Язык поддерживается, но неизвестно, офлайн ли голос (движок не сообщает). */
    AVAILABLE,
    /** Движок знает язык, но данные голоса не скачаны. */
    MISSING_DATA,
    /** Движок не поддерживает язык. */
    NOT_SUPPORTED,
    /** Движок не запущен. */
    UNKNOWN,
}

sealed interface SpeakResult {
    data object Done : SpeakResult
    data class LanguageUnavailable(val lang: String) : SpeakResult
    data class Error(val code: Int) : SpeakResult
    /** Движок не сообщил об окончании фразы за разумное время. */
    data object Timeout : SpeakResult
}

/**
 * Абстракция синтезатора речи. Реализация для Android — [AndroidTtsEngine];
 * в тестах используется подделка с виртуальным временем.
 */
interface SpeechEngine {
    val status: StateFlow<EngineStatus>

    /** Запускает движок при необходимости и ждёт готовности. false — запустить не удалось. */
    suspend fun awaitReady(): Boolean

    /** Произносит текст и возвращается, когда речь закончилась (или при ошибке). Отмена корутины прерывает речь. */
    suspend fun speak(text: String, lang: String, rate: Float): SpeakResult

    fun stop()
}
