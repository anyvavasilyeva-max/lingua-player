package io.github.linguaplayer.data.repo

import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntryQueryBuilder
import io.github.linguaplayer.importer.CsvWriter
import io.github.linguaplayer.importer.SampleCsv
import io.github.linguaplayer.importer.XlsxWriter
import java.io.OutputStream

/**
 * Экспорт словаря в CSV / XLSX в том же формате, что и импорт:
 * source, translation, type, tags (теги через «;»). Файл можно импортировать обратно.
 * Это не резервная копия: плейлисты, порядок и настройки сюда не входят (см. BackupRepository).
 */
class ExportRepository(private val db: AppDatabase) {

    /** Строки таблицы (с заголовком) для записей по фильтру — библиотека пары или плейлист. */
    suspend fun rows(filter: EntryFilter): List<List<String>> {
        val entries = db.entryDao().query(EntryQueryBuilder.build(filter))
        val tagNames = db.tagDao().getAll().associate { it.id to it.name }
        val links = db.entryDao().tagLinksForEntries(entries.map { it.id })
        return listOf(SampleCsv.HEADER) + entries.map { e ->
            listOf(
                e.source,
                e.translation,
                e.type.code,
                links[e.id].orEmpty().mapNotNull { tagNames[it] }.sorted().joinToString(";"),
            )
        }
    }

    fun writeCsv(rows: List<List<String>>, out: OutputStream) = out.write(CsvWriter.toBytes(rows))

    fun writeXlsx(sheetName: String, rows: List<List<String>>, out: OutputStream) =
        XlsxWriter.write(listOf(XlsxWriter.Sheet(sheetName, rows)), out)
}
