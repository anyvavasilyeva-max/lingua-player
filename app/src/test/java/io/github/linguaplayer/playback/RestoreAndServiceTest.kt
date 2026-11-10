package io.github.linguaplayer.playback

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.ui.HoldingSpeech
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class RestoreAndServiceTest {
    private val c get() = ApplicationProvider.getApplicationContext<LinguaApp>().container

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            // Главный поток Robolectric «на паузе»: время для delay/debounce сдвигаем явно.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            check(System.currentTimeMillis() < deadline) { "timeout" }
            Thread.sleep(20)
        }
    }

    private fun seed(): List<Long> = runBlocking {
        c.settings.updatePlayback { io.github.linguaplayer.player.PlaybackSettings() }
        val pair = c.languagePairs.getAll().first().id
        listOf("가" to "a", "나" to "b", "다" to "c", "라" to "d").map { (s, t) ->
            c.entries.save(EntryDraft(pairId = pair, source = s, translation = t, type = EntryType.WORD, tagIds = emptySet()))
        }
    }

    @Test
    fun queueAndPositionAreRestoredAfterRestart() {
        val ids = seed()
        // Как будто прошлый запуск сохранил перемешанную очередь, остановившись на 3-й записи.
        c.playbackStore.save(
            SavedPlayback("Кухня", null, ids, listOf(ids[2] to true, ids[0] to false, ids[3] to true, ids[1] to true), 2),
        )
        c.playerSpeechOverride = HoldingSpeech()
        val player = c.player
        waitUntil { player.state.value.hasQueue }
        val st = player.state.value
        assertEquals("Кухня", st.title)
        assertEquals(listOf("다", "가", "라", "나"), st.queue.map { it.source })
        assertEquals(listOf(true, false, true, true), st.queue.map { it.sourceFirst })
        assertEquals(2, st.index)
        assertFalse("после восстановления — на паузе", st.isPlaying)
    }

    @Test
    fun positionChangesArePersisted() {
        val ids = seed()
        c.playerSpeechOverride = HoldingSpeech()
        runBlocking { c.startPlayback("Библиотека", runBlocking { c.entries.getEntries(io.github.linguaplayer.data.db.EntryFilter()) }) }
        val player = c.player
        player.pause()
        player.next(); player.next()
        waitUntil { c.playbackStore.load()?.index == 2 }
        val saved = c.playbackStore.load()!!
        assertEquals(ids.size, saved.order.size)
        assertEquals("Библиотека", saved.title)
        player.clear()
        waitUntil { c.playbackStore.load() == null }
        assertNull(c.playbackStore.load())
    }

    @Test
    fun deletedEntriesAreSkippedOnRestore() {
        val ids = seed()
        c.playbackStore.save(SavedPlayback("X", null, ids, ids.map { it to true }, 3))
        runBlocking { c.entries.delete(listOf(ids[1])) }
        c.playerSpeechOverride = HoldingSpeech()
        val player = c.player
        waitUntil { player.state.value.hasQueue }
        assertEquals(3, player.state.value.queue.size)
        assertEquals("라", player.state.value.current!!.source)
    }

    @Test
    fun serviceCreatesMediaSession() {
        c.playerSpeechOverride = HoldingSpeech()
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val info = androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "io.github.linguaplayer", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false,
        )
        val session = service.onGetSession(info)
        assertNotNull(session)
        // Плеер сессии — наша обёртка над движком: состояние приходит из очереди.
        assertEquals(androidx.media3.common.Player.STATE_IDLE, session!!.player.playbackState)
        assertNotNull(session.sessionActivity) // нажатие на уведомление открывает приложение
        controller.destroy()
    }
}
