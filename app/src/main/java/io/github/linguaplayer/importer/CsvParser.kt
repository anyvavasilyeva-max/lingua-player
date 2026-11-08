package io.github.linguaplayer.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Разбор CSV по RFC 4180 с поблажками для файлов из Excel:
 * - значения в кавычках могут содержать разделители, переносы строк и удвоенные кавычки ("");
 * - концы строк CRLF, LF и CR;
 * - BOM в начале файла;
 * - разделитель «,», «;» или табуляция (определяется автоматически, можно задать явно).
 */
object CsvParser {

    data class Decoded(val text: String, val charset: String, val notUtf8: Boolean)

    /**
     * Декодирует байты: UTF-8 (с BOM или без), UTF-16 по BOM (Excel «Текст Юникод»).
     * Если файл не является корректным UTF-8 — пробуем Windows-1251 (так Excel на русской
     * Windows сохраняет «CSV (разделители — запятые)») и помечаем [Decoded.notUtf8].
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8", false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE", false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE", false)
        }
        return try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            Decoded(text, "UTF-8", false)
        } catch (_: CharacterCodingException) {
            Decoded(String(bytes, Charset.forName("windows-1251")), "windows-1251", true)
        }
    }

    /** Выбирает разделитель по первой непустой строке (вне кавычек). */
    fun detectDelimiter(text: String): Char {
        val candidates = charArrayOf(',', ';', '\t')
        val counts = IntArray(candidates.size)
        var inQuotes = false
        var sawContent = false
        for (ch in text) {
            if (ch == '"') inQuotes = !inQuotes
            if (!inQuotes) {
                if (ch == '\n' || ch == '\r') {
                    if (sawContent) break else continue
                }
                sawContent = true
                val i = candidates.indexOf(ch)
                if (i >= 0) counts[i]++
            }
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        return if (counts[best] == 0) ',' else candidates[best]
    }

    data class Result(val rows: List<List<String>>, val unterminatedQuote: Boolean)

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): Result {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false // поле уже содержит символы (для распознавания открывающей кавычки)
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        val n = text.length

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }

        while (i < n) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when {
                    ch == '"' && !fieldStarted -> { inQuotes = true; fieldStarted = true }
                    ch == delimiter -> endField()
                    ch == '\r' -> { endRow(); if (i + 1 < n && text[i + 1] == '\n') i++ }
                    ch == '\n' -> endRow()
                    else -> { field.append(ch); fieldStarted = true }
                }
            }
            i++
        }
        val unterminated = inQuotes
        // Последняя строка без перевода строки в конце.
        if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) endRow()
        return Result(rows, unterminated)
    }
}
