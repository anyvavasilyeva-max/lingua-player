package io.github.linguaplayer.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import io.github.linguaplayer.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Поведение плеера в «звуковой среде» Android:
 *
 * - **Audio focus.** При старте воспроизведения запрашивается фокус (USAGE_MEDIA / SPEECH).
 *   Входящий звонок, навигатор, голосовой ассистент, другое приложение с музыкой забирают фокус →
 *   пауза. Если потеря временная (звонок, подсказка навигатора) — после возврата фокуса
 *   воспроизведение продолжается с начала текущей записи. Постоянная потеря (другой плеер
 *   начал играть) — пауза без автопродолжения. «Приглушение» для речи не подходит —
 *   [AudioFocusRequest] запрашивается с willPauseWhenDucked, и система вместо приглушения
 *   присылает временную потерю.
 * - **Отключение наушников / Bluetooth** (ACTION_AUDIO_BECOMING_NOISY) → пауза, чтобы речь
 *   не зазвучала из динамика.
 * - **Wake lock.** Пока идёт воспроизведение, держится PARTIAL_WAKE_LOCK: речь синтезирует и
 *   проигрывает процесс TTS-движка, а паузы между фразами отсчитывает наш процесс — без
 *   wake lock при выключенном экране процессор засыпает и паузы растягиваются.
 */
class PlaybackGuard(
    context: Context,
    private val engine: PlayerEngine,
    scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val wakeLock: PowerManager.WakeLock =
        app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinguaPlayer:playback")
            .apply { setReferenceCounted(false) }

    private var hasFocus = false
    /** Пауза из-за временной потери фокуса — после возврата фокуса продолжить. */
    private var resumeOnGain = false
    /** Игнорировать собственную паузу, вызванную потерей фокуса, при отслеживании состояния. */
    private var pausedByFocus = false
    private var noisyRegistered = false
    /** true, пока пауза вызвана самим PlaybackGuard (а не пользователем). */
    private var internalPause = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && engine.state.value.isPlaying) {
                resumeOnGain = false
                pauseInternally()
            }
        }
    }

    init {
        // Пользователь сам поставил паузу (в т. ч. во время звонка) — после звонка не продолжать.
        engine.addPauseListener {
            if (!internalPause) {
                resumeOnGain = false
                pausedByFocus = false
            }
        }
        scope.launch {
            engine.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                if (playing) onStarted() else onStopped()
            }
        }
    }

    private fun onStarted() {
        pausedByFocus = false
        resumeOnGain = false
        if (!hasFocus) {
            val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasFocus = granted
            if (!granted) {
                // Например, идёт телефонный разговор — не начинаем говорить поверх.
                pauseInternally()
                return
            }
        }
        registerNoisy()
        acquireWakeLock()
    }

    private fun onStopped() {
        releaseWakeLock()
        unregisterNoisy()
        // При временной потере фокуса запрос не отзываем — ждём возвращения фокуса.
        if (!(pausedByFocus && resumeOnGain)) abandonFocus()
    }

    private fun pauseInternally() {
        internalPause = true
        try { engine.pause() } finally { internalPause = false }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnGain) {
                    resumeOnGain = false
                    pausedByFocus = false
                    engine.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Фокус временно у другого (звонок): ▶ во время звонка снова запросит фокус и получит отказ.
                hasFocus = false
                if (engine.state.value.isPlaying) {
                    resumeOnGain = true
                    pausedByFocus = true
                    pauseInternally()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                pausedByFocus = true
                hasFocus = false
                pauseInternally()
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        resumeOnGain = false
        pausedByFocus = false
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { app.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_MAX_MS)
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        /** Страховка от «вечного» wake lock: 6 часов непрерывного прослушивания. */
        const val WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L
    }
}
