package io.github.linguaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

@RunWith(AndroidJUnit4::class) // org.json — из Android
class PlaybackStateStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripKeepsOrderDirectionsAndIndex() {
        val file = File(tmp.root, "playback/queue.json")
        val store = PlaybackStateStore(file)
        val saved = SavedPlayback("Кухня «TOPIK»", 7, listOf(1, 2, 3, 4), listOf(3L to true, 1L to false, 4L to true, 2L to false), 2)
        store.save(saved)
        assertEquals(saved, store.load())
        store.save(saved.copy(playlistId = null, index = 0))
        assertNull(store.load()!!.playlistId)
        store.save(null)
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun corruptFileIsIgnored() {
        val file = File(tmp.root, "queue.json").apply { writeText("{not json") }
        assertNull(PlaybackStateStore(file).load())
    }
}
