package io.github.linguaplayer.playback

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.ui.HoldingSpeech
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * Кнопки гарнитуры/Bluetooth: система доставляет их как Intent ACTION_MEDIA_BUTTON
 * (через MediaButtonReceiver → MediaSessionService). Проверяется весь путь до движка.
 */
@RunWith(AndroidJUnit4::class)
class MediaButtonTest {
    private val app = ApplicationProvider.getApplicationContext<LinguaApp>()
    private val c get() = app.container
    private lateinit var service: ServiceController<PlaybackService>

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))

    private fun press(keyCode: Int) {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(app, PlaybackService::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))
            service.withIntent(intent).startCommand(0, 1)
            idle()
        }
    }

    @Before
    fun setUp() {
        c.playerSpeechOverride = HoldingSpeech()
        runBlocking {
            c.settings.updatePlayback { io.github.linguaplayer.player.PlaybackSettings() }
            val pair = c.languagePairs.getAll().first().id
            listOf("가" to "a", "나" to "b", "다" to "c").forEach { (s, t) ->
                c.entries.save(EntryDraft(pairId = pair, source = s, translation = t, type = EntryType.WORD, tagIds = emptySet()))
            }
            c.startPlayback("Тест", c.entries.getEntries(EntryFilter()))
        }
        c.player.pause()
        service = Robolectric.buildService(PlaybackService::class.java).create()
        idle()
    }

    @After
    fun tearDown() {
        c.player.pause()
        service.destroy()
    }

    @Test
    fun headsetButtonsControlPlayback() {
        assertFalse(c.player.state.value.isPlaying)
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue("▶/⏸ на наушниках запускает воспроизведение", c.player.state.value.isPlaying)

        press(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(1, c.player.state.value.index)

        press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        assertEquals(0, c.player.state.value.index)

        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertFalse(c.player.state.value.isPlaying)

        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertTrue(c.player.state.value.isPlaying)
    }
}
