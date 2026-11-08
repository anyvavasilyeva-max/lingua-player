package io.github.linguaplayer.importer

/** Запись CSV по RFC 4180: значения с запятой, кавычкой или переносом строки берутся в кавычки. */
object CsvWriter {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun escape(value: String, delimiter: Char = ','): String =
        if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun toText(rows: List<List<String>>, delimiter: Char = ','): String =
        rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(delimiter.toString()) { escape(it, delimiter) } }

    /** UTF-8 с BOM: так Excel правильно открывает корейский и кириллицу. */
    fun toBytes(rows: List<List<String>>, delimiter: Char = ','): ByteArray =
        UTF8_BOM + toText(rows, delimiter).toByteArray(Charsets.UTF_8)
}
