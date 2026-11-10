package io.github.linguaplayer.importer

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Запись простого XLSX (без формул и форматирования) без внешних библиотек.
 * Строки хранятся как inline-строки, первая строка выделена жирным и закреплена.
 * Файл открывается в Excel, Google Таблицах, LibreOffice и читается обратно [XlsxReader].
 */
object XlsxWriter {

    class Sheet(val name: String, val rows: List<List<String>>)

    fun write(sheets: List<Sheet>, out: OutputStream) {
        require(sheets.isNotEmpty())
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(sheets))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, sheet -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet.rows)) }
        }
    }

    /** Имя листа Excel: до 31 символа, без []:*?/\ */
    fun safeSheetName(name: String, index: Int): String {
        val cleaned = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31)
        return cleaned.ifEmpty { "Sheet${index + 1}" }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                // Недопустимые в XML 1.0 управляющие символы отбрасываются.
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun column(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    private fun sheetXml(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("""<cols><col min="1" max="2" width="36" customWidth="1"/><col min="3" max="3" width="10" customWidth="1"/><col min="4" max="4" width="30" customWidth="1"/></cols>""")
        sb.append("<sheetData>")
        rows.forEachIndexed { r, row ->
            sb.append("<row r=\"").append(r + 1).append("\">")
            row.forEachIndexed { c, value ->
                if (value.isEmpty()) return@forEachIndexed
                sb.append("<c r=\"").append(column(c)).append(r + 1).append("\" t=\"inlineStr\"")
                if (r == 0) sb.append(" s=\"1\"")
                sb.append("><is><t xml:space=\"preserve\">").append(esc(value)).append("</t></is></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun contentTypes(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..n) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        val used = HashSet<String>()
        sheets.forEachIndexed { i, s ->
            var name = safeSheetName(s.name, i)
            var k = 2
            while (!used.add(name.lowercase())) name = safeSheetName(s.name, i).take(27) + " ($k)".also { k++ }
            append("<sheet name=\"").append(esc(name)).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) {
            append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        append("<Relationship Id=\"rId${n + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
