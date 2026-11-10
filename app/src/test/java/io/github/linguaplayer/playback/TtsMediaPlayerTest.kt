package io.github.linguaplayer.playback

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.player.PlayerEngine
import io.github.linguaplayer.player.RepeatMode
import io.github.linguaplayer.player.source
import io.github.linguaplayer.ui.HoldingSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Media3 видит очередь движка как плейлист, а команды экрана блокировки/Bluetooth управляют движком. */
@RunWith(AndroidJUnit4::class)
class TtsMediaPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speech = HoldingSpeech()
    private val engine = PlayerEngine(scope, speech, kotlin.random.Random(1))
    private var repeatSet: RepeatMode? = null
    private val player = TtsMediaPlayer(engine, scope) { repeatSet = it }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun tearDown() {
        player.release()
        scope.cancel()
    }

    @Test
    fun exposesQueueAndMetadata() {
        assertEquals(Player.STATE_IDLE, player.playbackState)
        engine.load(source(), autoplay = false)
        idle()
        assertEquals(5, player.mediaItemCount)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals("포크", player.mediaMetadata.title.toString())
        assertEquals("Вилка", player.mediaMetadata.artist.toString())
        assertEquals("Кухня", player.mediaMetadata.albumTitle.toString())
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.isPlaying)
    }

    @Test
    fun mediaCommandsControlEngine() {
        engine.load(source(), autoplay = false)
        idle()
        player.play() // кнопка ▶ на экране блокировки / в наушниках
        idle()
        assertTrue(engine.state.value.isPlaying)
        assertTrue(player.isPlaying)
        assertEquals("포크", speech.spoken.last().first)

        player.seekToNext() // ⏭
        idle()
        assertEquals(1, engine.state.value.index)
        assertEquals("숟가락", speech.spoken.last().first)

        player.seekToPrevious() // ⏮
        idle()
        assertEquals(0, engine.state.value.index)

        player.seekTo(3, 0)
        idle()
        assertEquals(3, engine.state.value.index)

        player.pause()
        idle()
        assertFalse(engine.state.value.isPlaying)
        assertFalse(player.isPlaying)

        player.repeatMode = Player.REPEAT_MODE_ONE
        idle()
        assertEquals(RepeatMode.ONE, repeatSet)
    }

    @Test
    fun engineChangesAreReflected() {
        engine.load(source(), autoplay = false)
        idle()
        engine.next(); engine.next()
        idle()
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals("설거지를 하다", player.mediaMetadata.title.toString())
        // Последняя запись без повтора — ⏭ недоступна.
        engine.seekTo(4)
        idle()
        assertFalse(player.hasNextMediaItem())
        engine.updateSettings(engine.state.value.settings.copy(repeatMode = RepeatMode.ALL))
        idle()
        assertTrue(player.hasNextMediaItem())
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
    }
}
