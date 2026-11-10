package io.github.linguaplayer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.data.backup.BackupCodec
import io.github.linguaplayer.data.backup.BackupFormatException
import io.github.linguaplayer.data.backup.BackupRepository
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntrySort
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.data.repo.ExportRepository
import io.github.linguaplayer.data.repo.ImportRepository
import io.github.linguaplayer.data.repo.SettingsRepository
import io.github.linguaplayer.data.repo.SmartRules
import io.github.linguaplayer.importer.ColumnMapping
import io.github.linguaplayer.importer.CsvParser
import io.github.linguaplayer.importer.DuplicatePolicy
import io.github.linguaplayer.importer.ImportOptions
import io.github.linguaplayer.importer.XlsxReader
import io.github.linguaplayer.player.Direction
import io.github.linguaplayer.player.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings = SettingsRepository(context)
    private val coversDir = File(context.filesDir, "covers-test")
    private val source = TestDb()
    private val target = TestDb()

    @After
    fun tearDown() { source.close(); target.close() }

    private fun backupOf(t: TestDb) = BackupRepository(t.db, settings, coversDir, "test", t.clock)

    /** Наполняет [source]: 2 пары, теги, «Не помню», обычный и умный плейлисты, обложка, настройки. */
    private suspend fun fill(): Map<String, Long> {
        val t = source
        val ko = t.pairs.getAll().first().id
        val en = t.pairs.add("en-US", "ru-RU")
        val kitchen = t.tags.create("кухня").id
        val dont = t.tags.dontRememberTag()!!
        t.tags.rename(dont.id, "Трудные") // системный тег переименован
        fun d(pair: Long, s: String, tr: String, type: EntryType = EntryType.WORD, tags: Set<Long> = emptySet()) =
            EntryDraft(pairId = pair, source = s, translation = tr, type = type, tagIds = tags)
        val fork = t.entries.save(d(ko, "포크", "Вилка", tags = setOf(kitchen, dont.id)))
        val spoon = t.entries.save(d(ko, "숟가락", "Ложка", tags = setOf(kitchen)))
        val wash = t.entries.save(d(ko, "설거지를 하다", "Мыть посуду", EntryType.PHRASE, setOf(kitchen)))
        t.entries.save(d(en, "fork", "Вилка", tags = setOf(kitchen)))
        val list = t.playlists.create("Кухня", ko)
        t.playlists.addEntries(list, listOf(wash, fork, spoon)) // порядок важен
        t.playlists.markOpened(list)
        val cover = File(coversDir, "src.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        t.playlists.setCover(list, cover.absolutePath)
        val smart = t.playlists.create("Фразы кухни", ko, PlaylistKind.SMART, SmartRules(TypeFilter.PHRASES, setOf(kitchen), TagMatchMode.ALL))
        settings.updatePlayback { it.copy(direction = Direction.TARGET_TO_SOURCE, repeats = 3, repeatMode = RepeatMode.ALL, hideAnswer = true) }
        settings.setSeedColor(0xFFBF3737.toInt())
        settings.setActivePair(en)
        return mapOf("ko" to ko, "en" to en, "list" to list, "smart" to smart, "kitchen" to kitchen, "dont" to dont.id)
    }

    private suspend fun exportBytes(): ByteArray = ByteArrayOutputStream().also { backupOf(source).export(it) }.toByteArray()

    @Test
    fun fullRoundTripRestoresEverything() = runTest {
        val ids = fill()
        val bytes = exportBytes()
        // Цель уже содержит другие данные — они должны быть заменены.
        target.entries.save(EntryDraft(pairId = target.pairs.getAll().first().id, source = "old", translation = "старое", type = EntryType.WORD, tagIds = emptySet()))
        settings.updatePlayback { io.github.linguaplayer.player.PlaybackSettings() }
        settings.setSeedColor(0xFF00B2D1.toInt())

        val data = backupOf(target).inspect(ByteArrayInputStream(bytes))
        val result = backupOf(target).restore(data)
        assertEquals(4, result.entries)
        assertEquals(2, result.playlists)

        val t = target
        assertEquals(listOf("ko-KR", "en-US"), t.pairs.getAll().map { it.sourceLang })
        assertTrue(t.entries.getEntries(EntryFilter(text = "old")).isEmpty())
        assertEquals(setOf("포크", "숟가락", "설거지를 하다"), t.entries.getEntries(EntryFilter(pairId = ids["ko"])).map { it.source }.toSet())
        // Порядок плейлиста и id сохранены.
        val ordered = t.entries.getEntries(EntryFilter(pairId = ids["ko"], playlistId = ids["list"], sort = EntrySort.PLAYLIST_ORDER))
        assertEquals(listOf("설거지를 하다", "포크", "숟가락"), ordered.map { it.source })
        // Системный тег по ключу, с пользовательским именем; связи тегов.
        val sys = t.tags.dontRememberTag()!!
        assertEquals("Трудные", sys.name)
        assertEquals(TagEntity.SYSTEM_DONT_REMEMBER, sys.systemKey)
        val fork = t.entries.getEntries(EntryFilter(text = "포크")).single()
        assertEquals(setOf(ids["kitchen"], sys.id), t.entries.getDetails(fork.id)!!.tagIds)
        // Умный плейлист и его условия.
        val smart = t.playlists.get(ids["smart"]!!)!!
        assertEquals(PlaylistKind.SMART, smart.kind)
        assertEquals(TypeFilter.PHRASES, smart.smartType)
        assertEquals(TagMatchMode.ALL, smart.smartTagMode)
        assertEquals(listOf("설거지를 하다"), t.playlists.observeEntries(smart.id).first().map { it.entry.source })
        // Обложка: новый файл с теми же байтами.
        val list = t.playlists.get(ids["list"]!!)!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), File(list.coverPath!!).readBytes())
        assertTrue(list.lastOpenedAt != null)
        // Настройки.
        val p = settings.playback.first()
        assertEquals(Direction.TARGET_TO_SOURCE, p.direction)
        assertEquals(3, p.repeats)
        assertEquals(RepeatMode.ALL, p.repeatMode)
        assertTrue(p.hideAnswer)
        assertEquals(0xFFBF3737.toInt(), settings.seedColor.first())
        assertEquals(ids["en"], settings.activePairId.first())
        // Ключи поиска пересчитаны.
        assertEquals(1, t.entries.getEntries(EntryFilter(text = "ВИЛКА", pairId = ids["ko"])).size)
    }

    @Test
    fun backupJsonIsDocumentedFormatV1() = runTest {
        fill()
        val bytes = exportBytes()
        val json = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "backup.json" }
            JSONObject(z.readBytes().decodeToString())
        }
        assertEquals("lingua-player-backup", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        for (key in listOf("languagePairs", "tags", "entries", "playlists", "settings")) assertTrue(key, json.has(key))
    }

    @Test
    fun invalidFilesAreRejectedWithoutChanges() = runTest {
        fill()
        val before = source.entries.getEntries(EntryFilter()).size
        fun zipOf(json: String) = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("backup.json")); z.write(json.toByteArray()); z.closeEntry() }
        }.toByteArray()
        val cases = mapOf(
            "not a zip".toByteArray() to BackupFormatException.Reason.NOT_A_BACKUP,
            zipOf("""{"format":"something-else","version":1}""") to BackupFormatException.Reason.NOT_A_BACKUP,
            zipOf("""{"format":"lingua-player-backup","version":99}""") to BackupFormatException.Reason.NEWER_VERSION,
            zipOf("""{"format":"lingua-player-backup","version":1,"entries":"oops"}""") to BackupFormatException.Reason.CORRUPTED,
            zipOf("{broken") to BackupFormatException.Reason.CORRUPTED,
        )
        for ((bytes, reason) in cases) {
            try {
                backupOf(source).inspect(ByteArrayInputStream(bytes))
                fail("expected $reason")
            } catch (e: BackupFormatException) {
                assertEquals(reason, e.reason)
            }
        }
        assertEquals(before, source.entries.getEntries(EntryFilter()).size)
    }

    @Test
    fun failureDuringRestoreRollsBackAndKeepsCurrentData() = runTest {
        fill()
        val good = backupOf(source).inspect(ByteArrayInputStream(exportBytes()))
        val existing = target.entries.save(EntryDraft(pairId = target.pairs.getAll().first().id, source = "сохранится", translation = "x", type = EntryType.WORD, tagIds = emptySet()))
        // Две записи с одинаковым id — вставка упадёт посреди транзакции.
        val broken = good.copy(entries = good.entries + good.entries.first())
        val coversBefore = coversDir.listFiles()?.size ?: 0
        try {
            backupOf(target).restore(broken)
            fail("expected failure")
        } catch (e: Exception) {
            // ожидаемо
        }
        assertEquals("сохранится", target.entries.getDetails(existing)!!.entry.source)
        assertEquals(1, target.entries.getEntries(EntryFilter()).size)
        assertNull(target.tags.observeAll().first().firstOrNull { it.name == "кухня" })
        assertEquals("новые файлы обложек удалены", coversBefore, coversDir.listFiles()?.size ?: 0)
    }

    @Test
    fun csvAndXlsxExportReimportLosslessly() = runTest {
        val ids = fill()
        val exporter = ExportRepository(source.db)
        val rows = exporter.rows(EntryFilter(pairId = ids["ko"]))
        assertEquals(listOf("source", "translation", "type", "tags"), rows.first())
        assertEquals(listOf("설거지를 하다", "Мыть посуду", "phrase", "кухня"), rows.first { it[0] == "설거지를 하다" })
        assertEquals("Трудные;кухня", rows.first { it[0] == "포크" }[3])

        val csv = ByteArrayOutputStream().also { exporter.writeCsv(rows, it) }.toByteArray()
        val xlsx = ByteArrayOutputStream().also { exporter.writeXlsx("ko", rows, it) }.toByteArray()
        assertEquals(rows, CsvParser.parse(CsvParser.decode(csv).text).rows)
        assertEquals(rows, XlsxReader.read(xlsx).single().rows)

        // Импорт экспортированного файла в пустую БД даёт те же записи.
        val pair = target.pairs.getAll().first().id
        val r = ImportRepository(target.db, target.tags, target.clock)
            .import(CsvParser.parse(CsvParser.decode(csv).text).rows, ImportOptions(pair, ColumnMapping(0, 1, 2, 3), null, DuplicatePolicy.SKIP))
        assertEquals(3, r.imported)
        assertEquals(0, r.errorCount)
        assertFalse(target.entries.getEntries(EntryFilter(text = "포크")).isEmpty())
    }
}
