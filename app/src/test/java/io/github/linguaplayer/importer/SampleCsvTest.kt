package io.github.linguaplayer.importer

import io.github.linguaplayer.data.db.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SampleCsvTest {
    @Test
    fun builtInSampleImportsAsThreeEntries() {
        val bytes = SampleCsv.bytes()
        // UTF-8 с BOM — чтобы Excel правильно показал корейский.
        assertEquals(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3))

        val file = FileLoader.load(SampleCsv.FILE_NAME, bytes)
        assertFalse(file.notUtf8)
        assertEquals(',', file.csvDelimiter)
        val rows = file.tables.single().rows
        assertEquals(SampleCsv.rows(), rows)

        val mapping = ColumnGuesser.guess(rows.first())
        assertEquals(ColumnMapping(0, 1, 2, 3), mapping)
        val plan = ImportPlanner.plan(rows, ImportOptions(1, mapping, null, DuplicatePolicy.SKIP), emptyMap())
        assertEquals(3, plan.newCount)
        assertEquals(0, plan.errorCount)
        val data = plan.rows.map { it.data!! }
        assertEquals(listOf(EntryType.WORD, EntryType.PHRASE, EntryType.WORD), data.map { it.type })
        assertEquals(listOf(listOf("кухня"), listOf("кухня", "быт"), listOf("TOPIK 2급", "Сложные")), data.map { it.tags })
        assertEquals("Груша; живот; лодка", data[2].translation)
    }

    @Test
    fun writerQuotesOnlyWhenNeeded() {
        assertEquals("포크", CsvWriter.escape("포크"))
        assertEquals("\"Кастрюля, котелок\"", CsvWriter.escape("Кастрюля, котелок"))
        assertEquals("\"Сказал: \"\"да\"\"\"", CsvWriter.escape("Сказал: \"да\""))
        assertEquals("\"a\nb\"", CsvWriter.escape("a\nb"))
        val rows = listOf(listOf("x, y", "\"q\"", "line1\nline2"), listOf("", "a;b", "c"))
        assertEquals(rows, CsvParser.parse(CsvWriter.toText(rows), ',').rows)
    }
}
