package io.github.linguaplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class TtsEngineInfo(val packageName: String, val label: String)

/**
 * Реализация [SpeechEngine] поверх системного android.speech.tts.TextToSpeech.
 *
 * Важные особенности системного TTS, которые здесь учитываются:
 * - инициализация асинхронная и может не удаться (движок отключён/не установлен) → статус ERROR,
 *   повторная попытка при следующем воспроизведении;
 * - язык и скорость — глобальные настройки экземпляра, поэтому они выставляются перед каждой
 *   фразой (только если изменились); фразы произносятся строго по одной;
 * - окончание фразы приходит через UtteranceProgressListener из другого потока;
 * - некоторые движки иногда не присылают onDone — защита таймаутом;
 * - для языка по умолчанию может выбираться сетевой голос — если есть установленный офлайн-голос
 *   того же языка, выбирается он;
 * - звук идёт в поток «Медиа» (USAGE_MEDIA): регулируется громкостью музыки и уходит в Bluetooth.
 */
class AndroidTtsEngine(context: Context) : SpeechEngine {
    private val app = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ttsDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var enginePackage: String? = null
    private var currentLang: String? = null
    private var currentRate: Float? = null
    private val pending = ConcurrentHashMap<String, CancellableContinuation<SpeakResult>>()

    private val _status = MutableStateFlow(EngineStatus.NOT_STARTED)
    override val status: StateFlow<EngineStatus> = _status

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit
        override fun onDone(utteranceId: String) = finish(utteranceId, SpeakResult.Done)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = finish(utteranceId, SpeakResult.Error(TextToSpeech.ERROR))
        override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId, SpeakResult.Error(errorCode))
        override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId, SpeakResult.Error(STOPPED))
    }

    private fun finish(id: String, result: SpeakResult) {
        pending.remove(id)?.let { if (it.isActive) it.resume(result) }
    }

    /** Запускает (или перезапускает) движок. Вызывать из главного потока. */
    private fun start() {
        tts?.shutdown()
        currentLang = null
        currentRate = null
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        _status.value = EngineStatus.INITIALIZING
        val created = TextToSpeech(app, { code ->
            val ok = code == TextToSpeech.SUCCESS
            _status.value = if (ok) EngineStatus.READY else EngineStatus.ERROR
            deferred.complete(ok)
        }, enginePackage)
        // Слушатель и аудиоатрибуты можно задать до окончания инициализации.
        created.setOnUtteranceProgressListener(listener)
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts = created
    }

    override suspend fun awaitReady(): Boolean {
        val d = withContext(Dispatchers.Main) {
            if (_status.value == EngineStatus.NOT_STARTED || _status.value == EngineStatus.ERROR) start()
            ready!!
        }
        return withTimeoutOrNull(INIT_TIMEOUT_MS) { d.await() } ?: run {
            _status.value = EngineStatus.ERROR
            false
        }
    }

    /** Выбор движка (null — системный по умолчанию). Перезапускает TTS, если движок изменился. */
    suspend fun setEnginePackage(pkg: String?) {
        if (pkg == enginePackage && _status.value != EngineStatus.NOT_STARTED) return
        enginePackage = pkg
        withContext(Dispatchers.Main) {
            if (_status.value != EngineStatus.NOT_STARTED) start()
        }
    }

    fun selectedEnginePackage(): String? = enginePackage

    /** Установленные движки синтеза речи (нужен запущенный TTS). */
    fun engines(): List<TtsEngineInfo> =
        tts?.engines.orEmpty().map { TtsEngineInfo(it.name, it.label) }

    fun defaultEnginePackage(): String? = tts?.defaultEngine

    fun languageStatus(tag: String): LanguageStatus {
        val t = tts ?: return LanguageStatus.UNKNOWN
        if (_status.value != EngineStatus.READY) return LanguageStatus.UNKNOWN
        val locale = Locale.forLanguageTag(tag)
        return when (runCatching { t.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)) {
            TextToSpeech.LANG_NOT_SUPPORTED -> LanguageStatus.NOT_SUPPORTED
            TextToSpeech.LANG_MISSING_DATA -> LanguageStatus.MISSING_DATA
            else -> {
                val voices = runCatching { t.voices }.getOrNull()?.filter { it.locale.language == locale.language }
                when {
                    voices.isNullOrEmpty() -> LanguageStatus.AVAILABLE
                    voices.any { it.isOffline() } -> LanguageStatus.AVAILABLE_OFFLINE
                    else -> LanguageStatus.AVAILABLE_NETWORK_ONLY
                }
            }
        }
    }

    private fun Voice.isOffline() =
        !isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()

    override suspend fun speak(text: String, lang: String, rate: Float): SpeakResult {
        if (!awaitReady()) return SpeakResult.Error(TextToSpeech.ERROR)
        val t = tts ?: return SpeakResult.Error(TextToSpeech.ERROR)

        val prepared = withContext(ttsDispatcher) { prepare(t, lang, rate) }
        if (prepared != null) return prepared

        val timeout = SPEAK_TIMEOUT_BASE_MS + (text.length * SPEAK_TIMEOUT_PER_CHAR_MS / rate).toLong()
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()
                pending[id] = cont
                cont.invokeOnCancellation {
                    pending.remove(id)
                    t.stop()
                }
                val params = Bundle()
                val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                if (r != TextToSpeech.SUCCESS) finish(id, SpeakResult.Error(r))
            }
        } ?: run {
            t.stop()
            SpeakResult.Timeout
        }
    }

    /** Выставляет язык и скорость. Возвращает ошибку или null, если всё готово. */
    private fun prepare(t: TextToSpeech, lang: String, rate: Float): SpeakResult? {
        if (lang != currentLang) {
            val locale = Locale.forLanguageTag(lang)
            val r = t.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                currentLang = null
                return SpeakResult.LanguageUnavailable(lang)
            }
            preferOfflineVoice(t, locale)
            currentLang = lang
        }
        if (rate != currentRate) {
            t.setSpeechRate(rate)
            currentRate = rate
        }
        return null
    }

    private fun preferOfflineVoice(t: TextToSpeech, locale: Locale) {
        val current = runCatching { t.voice }.getOrNull() ?: return
        if (!current.isNetworkConnectionRequired) return
        val candidates = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == locale.language && it.isOffline() }
        val best = candidates.sortedWith(
            compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality },
        ).firstOrNull() ?: return
        runCatching { t.voice = best }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        _status.value = EngineStatus.NOT_STARTED
    }

    companion object {
        const val STOPPED = -100
        const val INIT_TIMEOUT_MS = 10_000L
        const val SPEAK_TIMEOUT_BASE_MS = 15_000L
        const val SPEAK_TIMEOUT_PER_CHAR_MS = 300L
    }
}
