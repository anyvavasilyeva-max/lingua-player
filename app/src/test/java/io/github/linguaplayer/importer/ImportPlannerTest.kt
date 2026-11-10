package io.github.linguaplayer.importer

import io.github.linguaplayer.data.db.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {
    private val header = listOf("source", "translation", "type", "tags")
    private val mapping = ColumnMapping(0, 1, 2, 3)
    private fun opts(policy: DuplicatePolicy = DuplicatePolicy.SKIP, defaultType: EntryType? = null, m: ColumnMapping = mapping) =
        ImportOptions(pairId = 1, mapping = m, defaultType = defaultType, policy = policy)

    @Test
    fun guessesColumnsByVariousNames() {
        assertEquals(ColumnMapping(0, 1, 2, 3), ColumnGuesser.guess(header))
        assertEquals(ColumnMapping(1, 2, null, 0), ColumnGuesser.guess(listOf("Теги", "Корейский", "Перевод")))
        assertEquals(ColumnMapping(2, 0, 1, null), ColumnGuesser.guess(listOf("Значение", "Тип", "단어")))
        assertEquals(ColumnMapping(0, 1, null, null), ColumnGuesser.guess(listOf("A", "B", "C")))
    }

    @Test
    fun validatesRowsAndKeepsGoodOnes() {
        val table = listOf(
            header,
            listOf("포크", "Вилка", "word", "кухня; быт ;;Кухня"),
            listOf("", "Ложка", "word", ""),
            listOf("숟가락", " ", "word", ""),
            listOf("젓가락", "Палочки", "noun", ""),
            listOf("   ", "", "", ""), // пустая строка — не считается
            listOf("설거지를 하다", "Мыть посуду", "Фраза", ""),
            listOf("물", "Вода"), // короткая строка, тип пуст, по умолчанию не выбран
        )
        val plan = ImportPlanner.plan(table, opts(), emptyMap())
        assertEquals(6, plan.found)
        assertEquals(2, plan.newCount)
        assertEquals(4, plan.errorCount)
        val first = plan.rows[0].data!!
        assertEquals(listOf("кухня", "быт"), first.tags)
        assertEquals(2, plan.rows[0].line)
        assertEquals(RowAction.Error(RowError.EMPTY_SOURCE), plan.rows[1].action)
        assertEquals(RowAction.Error(RowError.UNKNOWN_TYPE, "noun"), plan.rows[3].action)
        assertEquals(EntryType.PHRASE, plan.rows[4].data!!.type)
        assertEquals(RowError.NO_TYPE, (plan.rows[5].action as RowAction.Error).error)
    }

    @Test
    fun defaultTypeWhenNoTypeColumn() {
        val table = listOf(listOf("Фраза", "Значение"), listOf("밥 먹었어요?", "Ты поел?"))
        val plan = ImportPlanner.plan(table, opts(defaultType = EntryType.PHRASE, m = ColumnMapping(0, 1)), emptyMap())
        val d = plan.rows.single().data!!
        assertEquals(EntryType.PHRASE, d.type)
        assertFalse(d.typeFromFile)
    }

    @Test
    fun withoutHeaderFirstRowIsData() {
        val table = listOf(listOf("포크", "Вилка"))
        val o = opts(defaultType = EntryType.WORD, m = ColumnMapping(0, 1)).copy(hasHeader = false)
        assertEquals(1, ImportPlanner.plan(table, o, emptyMap()).newCount)
    }

    private val dupTable = listOf(
        header,
        listOf("포크", "Вилка", "word", "кухня"),
        listOf("포크 ", "Вилка (столовая)", "", "TOPIK2"),
        listOf("숟가락", "Ложка", "word", ""),
    )
    private val existing = mapOf("숟가락" to 42L)

    @Test
    fun skipPolicy() {
        val plan = ImportPlanner.plan(dupTable, opts(DuplicatePolicy.SKIP, EntryType.WORD), existing)
        assertEquals(RowAction.New, plan.rows[0].action)
        assertEquals(RowAction.Skip(SkipReason.DUPLICATE_IN_FILE), plan.rows[1].action)
        assertEquals(RowAction.Skip(SkipReason.DUPLICATE_IN_LIBRARY), plan.rows[2].action)
        assertEquals("Вилка", plan.rows[0].data!!.translation)
    }

    @Test
    fun updatePolicyMergesInFileDuplicates() {
        val plan = ImportPlanner.plan(dupTable, opts(DuplicatePolicy.UPDATE, EntryType.WORD), existing)
        val merged = plan.rows[0]
        assertEquals(RowAction.New, merged.action)
        assertEquals("Вилка (столовая)", merged.data!!.translation)
        assertEquals(listOf("кухня", "TOPIK2"), merged.data.tags)
        assertTrue(merged.data.typeFromFile)
        assertEquals(RowAction.Skip(SkipReason.MERGED_IN_FILE), plan.rows[1].action)
        assertEquals(RowAction.Update(42L), plan.rows[2].action)
    }

    @Test
    fun importAsNewPolicy() {
        val plan = ImportPlanner.plan(dupTable, opts(DuplicatePolicy.IMPORT_AS_NEW, EntryType.WORD), existing)
        assertEquals(3, plan.newCount)
    }
}
