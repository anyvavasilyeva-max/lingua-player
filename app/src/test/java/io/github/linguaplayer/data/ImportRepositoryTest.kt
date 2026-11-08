package io.github.linguaplayer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.ImportRepository
import io.github.linguaplayer.importer.ColumnMapping
import io.github.linguaplayer.importer.CsvParser
import io.github.linguaplayer.importer.DuplicatePolicy
import io.github.linguaplayer.importer.ImportOptions
import io.github.linguaplayer.importer.RowError
import io.github.linguaplayer.importer.RowAction
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {
    private lateinit var t: TestDb
    private lateinit var importer: ImportRepository
    private var pairId = 0L

    @Before
    fun setUp() = runTest {
        t = TestDb()
        importer = ImportRepository(t.db, t.tags, t.clock)
        pairId = t.pairs.getAll().single().id
    }

    @After
    fun tearDown() = t.close()

    private fun opts(policy: DuplicatePolicy) =
        ImportOptions(pairId, ColumnMapping(0, 1, 2, 3), defaultType = EntryType.WORD, policy = policy)

    private fun csv(text: String) = CsvParser.parse(text).rows

    @Test
    fun imports3500RowsInBatches() = runTest {
        val sb = StringBuilder("source,translation,type,tags\n")
        for (i in 1..3500) {
            sb.append("단어$i,\"Слово $i, вариант\",${if (i % 5 == 0) "phrase" else "word"},TOPIK ${i % 3 + 2}급;набор\n")
        }
        sb.append(",без слова,word,\n") // ошибка не мешает остальным
        val progress = mutableListOf<Int>()
        val result = importer.import(csv(sb.toString()), opts(DuplicatePolicy.SKIP)) { done, _ -> progress += done }

        assertEquals(3501, result.found)
        assertEquals(3500, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(1, result.errorCount)
        assertEquals(RowError.EMPTY_SOURCE, (result.errors.single().action as RowAction.Error).error)
        assertEquals(3502, result.errors.single().line)
        assertTrue("progress reported per batch", progress.size >= 8)
        assertEquals(3501, progress.last())

        val all = t.entries.getEntries(EntryFilter(pairId = pairId))
        assertEquals(3500, all.size)
        assertEquals(700, t.entries.getEntries(EntryFilter(type = io.github.linguaplayer.data.db.TypeFilter.PHRASES)).size)
        val names = t.tags.observeWithCountsOnce().associate { it.tag.name to it.usageCount }
        assertEquals(3500, names["набор"])
        assertEquals(3500, (names["TOPIK 2급"] ?: 0) + (names["TOPIK 3급"] ?: 0) + (names["TOPIK 4급"] ?: 0))
    }

    @Test
    fun reimportSkipDoesNotDuplicate() = runTest {
        val file = csv("source,translation,type,tags\n포크,Вилка,word,кухня\n숟가락,Ложка,word,\n")
        importer.import(file, opts(DuplicatePolicy.SKIP))
        val again = importer.import(file, opts(DuplicatePolicy.SKIP))
        assertEquals(0, again.imported)
        assertEquals(2, again.skipped)
        assertEquals(2, t.entries.getEntries(EntryFilter()).size)
    }

    @Test
    fun reimportUpdateMergesTagsAndKeepsPlaylists() = runTest {
        importer.import(csv("source,translation,type,tags\n포크,Вилка,word,кухня\n"), opts(DuplicatePolicy.SKIP))
        val entry = t.entries.getEntries(EntryFilter()).single()
        val playlist = t.playlists.create("Кухня")
        t.playlists.addEntries(playlist, listOf(entry.id))

        val r = importer.import(csv("source,translation,type,tags\n포크,\"Вилка; вилочка\",phrase,быт;КУХНЯ\n"), opts(DuplicatePolicy.UPDATE))
        assertEquals(1, r.updated)
        assertEquals(0, r.imported)

        val details = t.entries.getDetails(entry.id)!!
        assertEquals("Вилка; вилочка", details.entry.translation)
        assertEquals(EntryType.PHRASE, details.entry.type)
        assertEquals(setOf(playlist), details.playlistIds)
        val tagNames = t.tags.observeWithCountsOnce().filter { it.tag.id in details.tagIds }.map { it.tag.name }.toSet()
        assertEquals(setOf("кухня", "быт"), tagNames)
        // Поиск по новому переводу работает (обновлён translation_key).
        assertEquals(1, t.entries.getEntries(EntryFilter(text = "вилочка")).size)
    }

    @Test
    fun updateWithoutTypeColumnKeepsExistingType() = runTest {
        importer.import(csv("source,translation,type\n설거지를 하다,Мыть посуду,phrase\n"), opts(DuplicatePolicy.SKIP))
        val o = ImportOptions(pairId, ColumnMapping(0, 1), defaultType = EntryType.WORD, policy = DuplicatePolicy.UPDATE)
        importer.import(csv("source,translation\n설거지를 하다,Мыть посуду (вручную)\n"), o)
        val e = t.entries.getEntries(EntryFilter()).single()
        assertEquals(EntryType.PHRASE, e.type)
        assertEquals("Мыть посуду (вручную)", e.translation)
    }

    @Test
    fun importAsNewCreatesDuplicates() = runTest {
        val file = csv("source,translation,type\n배,Груша,word\n")
        importer.import(file, opts(DuplicatePolicy.SKIP))
        val r = importer.import(csv("source,translation,type\n배,Лодка,word\n"), opts(DuplicatePolicy.IMPORT_AS_NEW))
        assertEquals(1, r.imported)
        assertEquals(2, t.entries.getEntries(EntryFilter(text = "배")).size)
    }

    @Test
    fun duplicatesAreScopedToLanguagePair() = runTest {
        importer.import(csv("source,translation,type\nfork,Вилка,word\n"), opts(DuplicatePolicy.SKIP))
        val enPair = t.pairs.add("en-US", "ru-RU")
        val r = importer.import(csv("source,translation,type\nfork,Вилка,word\n"), opts(DuplicatePolicy.SKIP).copy(pairId = enPair))
        assertEquals(1, r.imported)
    }
}
