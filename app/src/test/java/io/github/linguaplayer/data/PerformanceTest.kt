package io.github.linguaplayer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.ImportRepository
import io.github.linguaplayer.importer.ColumnMapping
import io.github.linguaplayer.importer.DuplicatePolicy
import io.github.linguaplayer.importer.ImportOptions
import io.github.linguaplayer.player.PlaybackSettings
import io.github.linguaplayer.player.PlaybackSource
import io.github.linguaplayer.player.QueueBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Объём «×2 от реального»: 3 языковые пары по 6000 записей (18 000), по 2 тега на запись,
 * плейлист на 6000 записей. Замеры — на JVM под Robolectric (на телефоне цифры другие,
 * но порядок величин и узкие места видны). Пороги щедрые — тест ловит деградацию, а не мерит точно.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    private val t = TestDb()

    @After
    fun tearDown() = t.close()

    private inline fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val r = block()
        println("PERF $label: ${(System.nanoTime() - start) / 1_000_000} ms")
        return r
    }

    @Test
    fun eighteenThousandEntriesAcrossThreePairs() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val importer = ImportRepository(t.db, t.tags, t.clock)
        val ko = t.pairs.getAll().first().id
        val en = t.pairs.add("en-US", "ru-RU")
        val ja = t.pairs.add("ja-JP", "ru-RU")
        val n = 6000
        for ((pair, prefix) in listOf(ko to "단어", en to "word", ja to "単語")) {
            val rows = listOf(listOf("source", "translation", "type", "tags")) + (1..n).map { i ->
                listOf("$prefix$i", "перевод $i; вариант", if (i % 4 == 0) "phrase" else "word", "TOPIK ${i % 5 + 1};тема ${i % 30}")
            }
            val r = timed("import $n rows ($prefix)") {
                importer.import(rows, ImportOptions(pair, ColumnMapping(0, 1, 2, 3), EntryType.WORD, DuplicatePolicy.SKIP))
            }
            assertEquals(n, r.imported)
        }

        val all = timed("library list of one pair (6000 + tags)") { t.entries.observeEntries(EntryFilter(pairId = ko)).first() }
        assertEquals(n, all.size)
        assertTrue(all.first().tags.size == 2)
        timed("search '단어12'") { t.entries.observeEntries(EntryFilter(pairId = ko, text = "단어12")).first() }
        val tagIds = t.tags.observeUsedInPair(ko).first().filter { it.name.startsWith("TOPIK") }.take(2).map { it.id }.toSet()
        timed("filter 2 tags ALL + phrases") {
            t.entries.observeEntries(EntryFilter(pairId = ko, tagIds = tagIds, tagMode = TagMatchMode.ALL, type = TypeFilter.PHRASES)).first()
        }
        val playlist = t.playlists.create("Все корейские", ko)
        timed("add 6000 entries to playlist") { t.playlists.addEntries(playlist, all.map { it.entry.id }) }
        val inPlaylist = timed("open playlist of 6000") { t.playlists.observeEntries(playlist).first() }
        assertEquals(n, inPlaylist.size)
        timed("move entry in playlist of 6000") { t.playlists.move(playlist, 5999, 0) }
        val pairs = t.pairs.getAll().associateBy { it.id }
        val queue = timed("build shuffled queue of 6000") {
            QueueBuilder.build(PlaybackSource("x", inPlaylist.map { it.entry }, pairs), PlaybackSettings(order = io.github.linguaplayer.player.PlayOrder.SHUFFLE), Random(1))
        }
        assertEquals(n, queue.size)
        timed("re-import 6000 with UPDATE (merge tags)") {
            val rows = listOf(listOf("source", "translation", "type", "tags")) + (1..n).map { i -> listOf("단어$i", "новый $i", "word", "обновлено") }
            importer.import(rows, ImportOptions(ko, ColumnMapping(0, 1, 2, 3), EntryType.WORD, DuplicatePolicy.UPDATE))
        }
    }
}
