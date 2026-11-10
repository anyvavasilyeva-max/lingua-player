package io.github.linguaplayer.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class XlsxWriterTest {
    @Test
    fun writtenWorkbookReadsBack() {
        val words = listOf(
            listOf("source", "translation", "type", "tags"),
            listOf("포크", "Вилка", "word", "кухня;быт"),
            listOf("\"괜찮아요\"라고 했어요", "Сказал: <всё> & \"ok\"", "phrase", ""),
            listOf("밥 먹었어요?", "Ты поел?\n(приветствие)", "phrase", "TOPIK 2급"),
        )
        val phrases = listOf(listOf("A", "B"), listOf("x\u0001y", "z"))
        val out = ByteArrayOutputStream()
        XlsxWriter.write(listOf(XlsxWriter.Sheet("ko-KR → ru-RU", words), XlsxWriter.Sheet("Лист/2*", phrases)), out)

        System.getProperty("lingua.screenshots")?.let { java.io.File(it, "export_test.xlsx").apply { parentFile?.mkdirs() }.writeBytes(out.toByteArray()) }
        val sheets = XlsxReader.read(out.toByteArray())
        assertEquals(listOf("ko-KR → ru-RU", "Лист 2"), sheets.map { it.name })
        // Пустые ячейки в конце строки XLSX не хранит — строка короче, импорт это учитывает.
        assertEquals(words.map { r -> r.dropLastWhile { it.isEmpty() } }, sheets[0].rows)
        assertEquals(listOf(listOf("A", "B"), listOf("xy", "z")), sheets[1].rows) // управляющий символ удалён
    }

    @Test
    fun emptyCellsKeepColumnPositions() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(listOf(XlsxWriter.Sheet("S", listOf(listOf("a", "", "c")))), out)
        assertEquals(listOf("a", "", "c"), XlsxReader.read(out.toByteArray()).single().rows.single())
    }
}
