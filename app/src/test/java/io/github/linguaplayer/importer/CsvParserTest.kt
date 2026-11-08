package io.github.linguaplayer.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvParserTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun parsesKoreanSample() {
        val decoded = CsvParser.decode(resource("sample_ko_ru.csv"))
        assertFalse(decoded.notUtf8)
        val rows = CsvParser.parse(decoded.text).rows
        assertEquals(listOf("source", "translation", "type", "tags"), rows[0])
        assertEquals(listOf("포크", "Вилка", "word", "кухня;быт"), rows[1])
        assertEquals("Кастрюля, котелок", rows[4][1])
        assertEquals("\"괜찮아요\"라고 했어요", rows[7][0])
        assertEquals("Сказал: \"всё в порядке\"", rows[7][1])
        assertEquals("Груша; живот; лодка", rows[8][1])
        assertEquals(9, rows.size)
    }

    @Test
    fun handlesQuotesCommasNewlinesAndCrlf() {
        val text = "a,b,c\r\n\"x, y\",\"he said \"\"hi\"\"\",\"line1\nline2\"\r\nlast,,\"\""
        val r = CsvParser.parse(text, ',')
        assertFalse(r.unterminatedQuote)
        assertEquals(listOf("a", "b", "c"), r.rows[0])
        assertEquals(listOf("x, y", "he said \"hi\"", "line1\nline2"), r.rows[1])
        assertEquals(listOf("last", "", ""), r.rows[2])
        assertEquals(3, r.rows.size)
    }

    @Test
    fun detectsSemicolonAndTab() {
        assertEquals(';', CsvParser.detectDelimiter("source;translation;type\n포크;Вилка;word"))
        assertEquals('\t', CsvParser.detectDelimiter("source\ttranslation\n포크\tВилка"))
        assertEquals(',', CsvParser.detectDelimiter("source,translation,tags\n포크,Вилка,\"a;b;c;d\""))
        // Точки с запятой внутри кавычек в заголовке не учитываются.
        assertEquals(',', CsvParser.detectDelimiter("\"a;b;c\",d\n"))
    }

    @Test
    fun semicolonFileWithQuotedTags() {
        val r = CsvParser.parse("포크;Вилка;word;\"кухня;быт\"\n", ';').rows
        assertEquals(listOf(listOf("포크", "Вилка", "word", "кухня;быт")), r)
    }

    @Test
    fun stripsBomAndDecodesUtf16() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "포크,Вилка".toByteArray()
        assertEquals("포크,Вилка", CsvParser.decode(utf8Bom).text)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "포크\tВилка".toByteArray(Charsets.UTF_16LE)
        assertEquals("포크\tВилка", CsvParser.decode(utf16).text)
    }

    @Test
    fun fallsBackToWindows1251() {
        val bytes = "слово,перевод".toByteArray(charset("windows-1251"))
        val d = CsvParser.decode(bytes)
        assertTrue(d.notUtf8)
        assertEquals("слово,перевод", d.text)
    }

    @Test
    fun unterminatedQuoteIsReportedButKept() {
        val r = CsvParser.parse("a,\"b\nc", ',')
        assertTrue(r.unterminatedQuote)
        assertEquals(listOf("a", "b\nc"), r.rows[0])
    }
}
