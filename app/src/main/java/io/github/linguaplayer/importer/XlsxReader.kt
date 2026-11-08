package io.github.linguaplayer.importer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Минимальное чтение XLSX (Office Open XML) без внешних библиотек.
 *
 * XLSX — это ZIP-архив с XML-файлами. Нужны:
 *  - xl/workbook.xml           — список листов и их r:id;
 *  - xl/_rels/workbook.xml.rels — где лежит XML каждого листа;
 *  - xl/sharedStrings.xml      — общая таблица строк (большинство текстовых ячеек ссылаются на неё);
 *  - xl/worksheets/sheetN.xml   — сами ячейки.
 * Используется SAX-парсер, встроенный и в Android, и в JVM. Читаются только значения
 * (формулы — их последний вычисленный результат); форматирование игнорируется.
 * Даты Excel хранит как числа — они будут прочитаны как числа.
 */
object XlsxReader {

    class Sheet(val name: String, val rows: List<List<String>>)

    class XlsxException(message: String) : IOException(message)

    /** Ограничения против повреждённых файлов и «ZIP-бомб». */
    private const val MAX_ENTRY_BYTES = 200L * 1024 * 1024

    fun read(bytes: ByteArray): List<Sheet> = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): List<Sheet> {
        val entries = unzip(input)
        val workbook = entries["xl/workbook.xml"] ?: throw XlsxException("xl/workbook.xml not found")
        val sheetsMeta = parseWorkbook(workbook)
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let(::parseRels).orEmpty()
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()

        return sheetsMeta.mapIndexed { index, (name, relId) ->
            val target = rels[relId] ?: "worksheets/sheet${index + 1}.xml"
            val path = resolve(target)
            val xml = entries[path] ?: throw XlsxException("sheet not found: $path")
            Sheet(name, parseSheet(xml, shared))
        }
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.trimStart('/')
                if (entry.isDirectory || !name.endsWith(".xml") && !name.endsWith(".rels")) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val r = zip.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_ENTRY_BYTES) throw XlsxException("entry too large: $name")
                    out.write(buf, 0, r)
                }
                result[name] = out.toByteArray()
            }
        }
        if (result.isEmpty()) throw XlsxException("not a ZIP/XLSX file")
        return result
    }

    private fun resolve(target: String): String =
        if (target.startsWith("/")) target.trimStart('/') else "xl/" + target.removePrefix("./")

    private fun sax(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // Защита от XXE: внешние сущности не нужны. Не все реализации знают эти флаги.
        listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
        ).forEach { runCatching { factory.setFeature(it, false) } }
        val parser = factory.newSAXParser()
        parser.parse(InputSource(ByteArrayInputStream(xml)), handler)
    }

    /** Имя атрибута без префикса пространства имён (r:id → id). */
    private fun Attributes.byLocalName(name: String): String? {
        for (i in 0 until length) {
            val local = getLocalName(i).ifEmpty { getQName(i).substringAfter(':') }
            if (local == name) return getValue(i)
        }
        return null
    }

    private fun localOf(localName: String, qName: String) = localName.ifEmpty { qName.substringAfter(':') }

    private fun parseWorkbook(xml: ByteArray): List<Pair<String, String>> {
        val sheets = ArrayList<Pair<String, String>>()
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, a: Attributes) {
                if (localOf(localName, qName) == "sheet") {
                    sheets += (a.byLocalName("name") ?: "Sheet${sheets.size + 1}") to (a.byLocalName("id") ?: "")
                }
            }
        })
        return sheets
    }

    private fun parseRels(xml: ByteArray): Map<String, String> {
        val map = HashMap<String, String>()
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, a: Attributes) {
                if (localOf(localName, qName) == "Relationship") {
                    val id = a.byLocalName("Id")
                    val target = a.byLocalName("Target")
                    if (id != null && target != null) map[id] = target
                }
            }
        })
        return map
    }

    /** Общие строки. Фонетические подсказки (rPh) пропускаются. */
    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val list = ArrayList<String>()
        sax(xml, object : DefaultHandler() {
            val sb = StringBuilder()
            var inT = false
            var phoneticDepth = 0
            override fun startElement(uri: String, localName: String, qName: String, a: Attributes) {
                when (localOf(localName, qName)) {
                    "si" -> sb.setLength(0)
                    "rPh" -> phoneticDepth++
                    "t" -> inT = phoneticDepth == 0
                }
            }
            override fun endElement(uri: String, localName: String, qName: String) {
                when (localOf(localName, qName)) {
                    "si" -> list += sb.toString()
                    "rPh" -> phoneticDepth--
                    "t" -> inT = false
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.appendRange(ch, start, start + length)
            }
        })
        return list
    }

    /** "C12" → 2 (номер столбца с нуля). */
    internal fun columnIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z' && ch !in 'a'..'z') break
            col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return col - 1
    }

    private fun formatNumber(v: String): String = try {
        BigDecimal(v.trim()).stripTrailingZeros().toPlainString()
    } catch (_: NumberFormatException) {
        v
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        sax(xml, object : DefaultHandler() {
            var row: ArrayList<String>? = null
            var rowNumber = 0
            var cellCol = 0
            var cellType: String? = null
            val value = StringBuilder()
            var capture = false
            var inInline = false
            var phoneticDepth = 0

            override fun startElement(uri: String, localName: String, qName: String, a: Attributes) {
                when (localOf(localName, qName)) {
                    "row" -> {
                        val r = a.byLocalName("r")?.toIntOrNull() ?: (rowNumber + 1)
                        // Пропущенные пустые строки сохраняем как пустые — номера строк совпадут с Excel.
                        while (rowNumber + 1 < r) { rows += emptyList<String>(); rowNumber++ }
                        rowNumber = r
                        row = ArrayList()
                    }
                    "c" -> {
                        val current = row ?: return
                        cellCol = a.byLocalName("r")?.let(::columnIndex)?.takeIf { it >= 0 } ?: current.size
                        cellType = a.byLocalName("t")
                        value.setLength(0)
                    }
                    "v" -> capture = true
                    "is" -> inInline = true
                    "t" -> if (inInline && phoneticDepth == 0) capture = true
                    "rPh" -> phoneticDepth++
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when (localOf(localName, qName)) {
                    "v", "t" -> capture = false
                    "is" -> inInline = false
                    "rPh" -> phoneticDepth--
                    "c" -> {
                        val current = row ?: return
                        val raw = value.toString()
                        val text = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "inlineStr", "str" -> raw
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "e" -> ""
                            else -> if (raw.isEmpty()) "" else formatNumber(raw)
                        }
                        while (current.size < cellCol) current += ""
                        if (current.size == cellCol) current += text else current[cellCol] = text
                    }
                    "row" -> { row?.let { rows += it }; row = null }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) value.appendRange(ch, start, start + length)
            }
        })
        return rows
    }
}
