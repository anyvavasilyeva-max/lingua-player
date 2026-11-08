package io.github.linguaplayer.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    @Test
    fun readsMultiSheetWorkbookCreatedByOpenpyxl() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sample_ko_ru.xlsx")!!.readBytes()
        val sheets = XlsxReader.read(bytes)
        assertEquals(listOf("Слова", "Фразы"), sheets.map { it.name })
        assertEquals(listOf("Корейский", "Перевод", "Тип", "Теги"), sheets[0].rows[0])
        assertEquals(listOf("포크", "Вилка", "word", "кухня;быт"), sheets[0].rows[1])
        assertEquals("Кастрюля, котелок", sheets[0].rows[3][1])
        assertEquals(listOf("Фраза", "Значение", "Метки"), sheets[1].rows[0])
        assertEquals("Ты поел?\n(приветствие)", sheets[1].rows[3][1])
    }

    /** Файл «как из Excel»: rich text, фонетика, inline-строки, пропуски ячеек и строк, числа. */
    @Test
    fun handlesExcelSpecificCells() {
        val workbook = """<?xml version="1.0" encoding="UTF-8"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Лист1" sheetId="1" r:id="rId7"/></sheets></workbook>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/data.xml"/>
</Relationships>"""
        val shared = """<?xml version="1.0" encoding="UTF-8"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
<si><t>source</t></si>
<si><r><rPr><b/></rPr><t>설거지를</t></r><r><t xml:space="preserve"> 하다</t></r><rPh sb="0" eb="1"><t>ソルゴジ</t></rPh></si>
<si><t>Мыть посуду &amp; тарелки</t></si>
</sst>"""
        val sheet = """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="inlineStr"><is><t>count</t></is></c></row>
<row r="3"><c r="A3" t="s"><v>1</v></c><c r="B3" t="s"><v>2</v></c><c r="C3"><v>3.50</v></c><c r="D3" t="b"><v>1</v></c></row>
<row r="4"><c r="B4" t="str"><f>A1</f><v>formula result</v></c><c r="C4"><v>1E+3</v></c></row>
</sheetData></worksheet>"""
        val bytes = zip(
            "[Content_Types].xml" to "<Types/>",
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to rels,
            "xl/sharedStrings.xml" to shared,
            "xl/worksheets/data.xml" to sheet,
        )
        val s = XlsxReader.read(bytes).single()
        assertEquals("Лист1", s.name)
        assertEquals(listOf("source", "", "count"), s.rows[0])
        assertEquals(emptyList<String>(), s.rows[1])
        assertEquals(listOf("설거지를 하다", "Мыть посуду & тарелки", "3.5", "TRUE"), s.rows[2])
        assertEquals(listOf("", "formula result", "1000"), s.rows[3])
    }

    @Test
    fun reads10000RowSheet() {
        val n = 10_000
        val shared = StringBuilder("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val sheet = StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        for (i in 0 until n) {
            shared.append("<si><t>단어$i</t></si><si><t>Слово $i</t></si>")
            sheet.append("<row r=\"${i + 1}\"><c r=\"A${i + 1}\" t=\"s\"><v>${2 * i}</v></c><c r=\"B${i + 1}\" t=\"s\"><v>${2 * i + 1}</v></c></row>")
        }
        shared.append("</sst>"); sheet.append("</sheetData></worksheet>")
        val bytes = zip(
            "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Big\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/sharedStrings.xml" to shared.toString(),
            "xl/worksheets/sheet1.xml" to sheet.toString(),
        )
        val start = System.nanoTime()
        val rows = XlsxReader.read(bytes).single().rows
        val ms = (System.nanoTime() - start) / 1_000_000
        println("XLSX 10000 rows parsed in $ms ms")
        assertEquals(n, rows.size)
        assertEquals(listOf("단어9999", "Слово 9999"), rows.last())
    }

    @Test
    fun columnIndexFromReference() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(25, XlsxReader.columnIndex("Z9"))
        assertEquals(26, XlsxReader.columnIndex("AA10"))
        assertEquals(701, XlsxReader.columnIndex("ZZ1"))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsNonXlsx() {
        XlsxReader.read("source,translation".toByteArray())
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in files) {
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
