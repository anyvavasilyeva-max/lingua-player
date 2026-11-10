package io.github.linguaplayer.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.player.PlayerEngine
import io.github.linguaplayer.player.source
import io.github.linguaplayer.ui.HoldingSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/** Звонки, другие приложения, отключение наушников, wake lock. */
@RunWith(AndroidJUnit4::class)
class PlaybackGuardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = PlayerEngine(scope, HoldingSpeech(), kotlin.random.Random(1))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun focusListener() = shadowOf(audioManager).lastAudioFocusRequest!!.listener!!
    private fun wakeLockHeld() = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    @Before
    fun setUp() {
        PlaybackGuard(context, engine, scope)
        engine.load(source(), autoplay = false)
        idle()
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun requestsFocusAndWakeLockWhilePlaying() {
        engine.play()
        idle()
        assertNotNull(shadowOf(audioManager).lastAudioFocusRequest)
        assertTrue(wakeLockHeld())
        engine.pause()
        idle()
        assertFalse(wakeLockHeld())
    }

    @Test
    fun incomingCallPausesAndResumesAfter() {
        engine.play(); idle()
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) // звонок
        idle()
        assertFalse(engine.state.value.isPlaying)
        assertFalse(wakeLockHeld())
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) // звонок завершён
        idle()
        assertTrue(engine.state.value.isPlaying)
    }

    @Test
    fun duckRequestAlsoPausesSpeech() {
        engine.play(); idle()
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        idle()
        assertFalse(engine.state.value.isPlaying)
    }

    @Test
    fun otherPlayerTakesFocusPermanently() {
        engine.play(); idle()
        val listener = focusListener()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        assertFalse(engine.state.value.isPlaying)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idle()
        assertFalse("после постоянной потери фокуса не продолжаем сами", engine.state.value.isPlaying)
    }

    @Test
    fun userPauseDuringCallDoesNotAutoResume() {
        engine.play(); idle()
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        engine.pause() // пользователь сам нажал паузу
        idle()
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idle()
        // Фокус был отдан при паузе пользователя → продолжение не происходит.
        assertFalse(engine.state.value.isPlaying)
    }

    @Test
    fun headphonesUnpluggedPauses() {
        engine.play(); idle()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY).setPackage(context.packageName))
        idle()
        assertFalse(engine.state.value.isPlaying)
    }

    @Test
    fun focusDeniedDoesNotStartSpeaking() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        engine.play(); idle()
        assertFalse(engine.state.value.isPlaying)
    }

    @Test
    fun playPressedDuringCallDoesNotSpeakOverCall() {
        engine.play(); idle()
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        engine.play() // нажали ▶ во время разговора
        idle()
        assertFalse(engine.state.value.isPlaying)
    }
}
