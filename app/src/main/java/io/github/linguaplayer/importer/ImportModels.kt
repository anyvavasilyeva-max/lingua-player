package io.github.linguaplayer.importer

import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.util.TextKeys

/** Что делать, если слово уже есть в библиотеке (та же языковая пара и то же слово). */
enum class DuplicatePolicy {
    /** Оставить существующую запись как есть. */
    SKIP,
    /** Обновить перевод (и тип, если он есть в файле), теги объединить. Связи с плейлистами сохраняются. */
    UPDATE,
    /** Добавить как отдельную запись. */
    IMPORT_AS_NEW,
}

/** Номера столбцов файла (с нуля) для полей приложения. null — столбца нет. */
data class ColumnMapping(
    val source: Int,
    val translation: Int,
    val type: Int? = null,
    val tags: Int? = null,
)

data class ImportOptions(
    val pairId: Long,
    val mapping: ColumnMapping,
    /** Тип для записей без столбца type (или с пустой ячейкой). null — пользователь не выбрал. */
    val defaultType: EntryType?,
    val policy: DuplicatePolicy,
    /** Первая строка таблицы — заголовки. */
    val hasHeader: Boolean = true,
)

/** Строка файла после сопоставления столбцов. [line] — номер строки в файле (с 1), как в Excel. */
data class ParsedRow(
    val line: Int,
    val source: String,
    val translation: String,
    val type: EntryType,
    /** Тип взят из файла (а не из значения по умолчанию) — только тогда он перезаписывает существующий. */
    val typeFromFile: Boolean,
    val tags: List<String>,
) {
    val key: String get() = TextKeys.key(source)
}

enum class RowError { EMPTY_SOURCE, EMPTY_TRANSLATION, UNKNOWN_TYPE, NO_TYPE, DATABASE }
enum class SkipReason { DUPLICATE_IN_LIBRARY, DUPLICATE_IN_FILE, MERGED_IN_FILE }

sealed interface RowAction {
    data object New : RowAction
    data class Update(val entryId: Long) : RowAction
    data class Skip(val reason: SkipReason) : RowAction
    data class Error(val error: RowError, val value: String = "") : RowAction
}

data class PlannedRow(val line: Int, val data: ParsedRow?, val action: RowAction)

data class ImportPlan(val rows: List<PlannedRow>) {
    val found get() = rows.size
    val newCount get() = rows.count { it.action is RowAction.New }
    val updateCount get() = rows.count { it.action is RowAction.Update }
    val skipCount get() = rows.count { it.action is RowAction.Skip }
    val errorCount get() = rows.count { it.action is RowAction.Error }
}

data class ImportResult(
    val found: Int,
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    /** Строки с ошибками (номер строки → причина). */
    val errors: List<PlannedRow>,
) {
    val errorCount get() = errors.size
}

/** Таблица, прочитанная из файла: CSV или один лист XLSX. */
data class SourceTable(val name: String, val rows: List<List<String>>) {
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

object ColumnGuesser {
    private val sourceNames = setOf(
        "source", "word", "term", "front", "original", "korean", "слово", "фраза", "оригинал",
        "корейский", "иностранное", "термин", "한국어", "단어", "표현",
    )
    private val translationNames = setOf(
        "translation", "meaning", "back", "russian", "definition", "перевод", "значение",
        "русский", "смысл", "뜻", "번역", "의미",
    )
    private val typeNames = setOf("type", "kind", "тип", "вид", "타입", "종류")
    private val tagNames = setOf("tags", "tag", "labels", "category", "теги", "тег", "метки", "метка", "категория", "태그")

    /** Угадывает сопоставление по заголовкам; если не удалось — первые два столбца. */
    fun guess(header: List<String>): ColumnMapping {
        val keys = header.map { TextKeys.key(it) }
        fun find(names: Set<String>, exclude: Set<Int> = emptySet()): Int? =
            keys.indices.firstOrNull { it !in exclude && keys[it] in names }

        val type = find(typeNames)
        val tags = find(tagNames, setOfNotNull(type))
        val taken = setOfNotNull(type, tags)
        val source = find(sourceNames, taken) ?: (0 until header.size).firstOrNull { it !in taken } ?: 0
        val translation = find(translationNames, taken + source)
            ?: (0 until header.size).firstOrNull { it !in taken && it != source } ?: 1
        return ColumnMapping(source, translation, type, tags)
    }
}

object TypeValues {
    private val word = setOf("word", "w", "слово", "сл", "сл.", "단어", "낱말")
    private val phrase = setOf(
        "phrase", "p", "sentence", "expression", "фраза", "выражение", "предложение", "фр", "фр.",
        "구", "문장", "표현",
    )

    fun parse(value: String): EntryType? {
        val k = TextKeys.key(value)
        return when (k) {
            in word -> EntryType.WORD
            in phrase -> EntryType.PHRASE
            else -> null
        }
    }
}
