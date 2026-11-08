package io.github.linguaplayer.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Прочитанный файл: CSV (одна таблица) или XLSX (таблица на каждый лист). */
data class LoadedFile(
    val name: String,
    val kind: Kind,
    val tables: List<SourceTable>,
    /** Для CSV: исходный текст (чтобы перечитать с другим разделителем). */
    val csvText: String? = null,
    val csvDelimiter: Char? = null,
    val encoding: String? = null,
    val notUtf8: Boolean = false,
    val unterminatedQuote: Boolean = false,
) {
    enum class Kind { CSV, XLSX }
}

class ImportFileException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { TOO_LARGE, OLD_XLS, UNREADABLE, EMPTY }
}

object FileLoader {
    const val MAX_BYTES = 50 * 1024 * 1024

    /** Читает файл, выбранный через системный диалог (Storage Access Framework). */
    fun readUri(context: Context, uri: Uri): Pair<String, ByteArray> {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > MAX_BYTES) throw ImportFileException(ImportFileException.Reason.TOO_LARGE)
            }
            out.toByteArray()
        } ?: throw ImportFileException(ImportFileException.Reason.UNREADABLE)
        return name to bytes
    }

    fun load(name: String, bytes: ByteArray): LoadedFile {
        if (bytes.isEmpty()) throw ImportFileException(ImportFileException.Reason.EMPTY)
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
        val isOle = bytes.size > 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
        if (isOle) throw ImportFileException(ImportFileException.Reason.OLD_XLS)
        if (isZip) {
            val sheets = try {
                XlsxReader.read(bytes)
            } catch (e: Exception) {
                throw ImportFileException(ImportFileException.Reason.UNREADABLE)
            }
            return LoadedFile(name, LoadedFile.Kind.XLSX, sheets.map { SourceTable(it.name, it.rows) })
        }
        val decoded = CsvParser.decode(bytes)
        return csv(name, decoded.text, null, decoded.charset, decoded.notUtf8)
    }

    /** (Пере)разбор CSV с заданным разделителем; null — определить автоматически. */
    fun csv(name: String, text: String, delimiter: Char?, encoding: String?, notUtf8: Boolean): LoadedFile {
        val d = delimiter ?: CsvParser.detectDelimiter(text)
        val parsed = CsvParser.parse(text, d)
        return LoadedFile(
            name = name,
            kind = LoadedFile.Kind.CSV,
            tables = listOf(SourceTable(name, parsed.rows)),
            csvText = text,
            csvDelimiter = d,
            encoding = encoding,
            notUtf8 = notUtf8,
            unterminatedQuote = parsed.unterminatedQuote,
        )
    }
}
