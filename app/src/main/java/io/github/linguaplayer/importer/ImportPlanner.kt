package io.github.linguaplayer.importer

import io.github.linguaplayer.util.TextKeys

/**
 * Чистая логика импорта (без БД): сопоставляет строки файла с полями, проверяет их и решает,
 * что сделать с каждой строкой. Одна и та же функция используется для предпросмотра и
 * для самого импорта — поэтому предпросмотр точно совпадает с результатом.
 */
object ImportPlanner {

    /**
     * @param existing нормализованное слово → id существующей записи в выбранной языковой паре.
     */
    fun plan(table: List<List<String>>, options: ImportOptions, existing: Map<String, Long>): ImportPlan {
        val m = options.mapping
        val startIndex = if (options.hasHeader) 1 else 0
        val result = ArrayList<PlannedRow>()
        // Ключ слова → индекс первой строки в result с этим словом (для повторов внутри файла).
        val firstInFile = HashMap<String, Int>()

        for (i in startIndex until table.size) {
            val cells = table[i]
            if (cells.all { it.isBlank() }) continue // полностью пустые строки не считаются
            val line = i + 1
            val source = TextKeys.clean(cells.getOrElse(m.source) { "" })
            val translation = TextKeys.clean(cells.getOrElse(m.translation) { "" })
            val typeCell = m.type?.let { cells.getOrElse(it) { "" }.trim() }.orEmpty()

            if (source.isEmpty()) { result += PlannedRow(line, null, RowAction.Error(RowError.EMPTY_SOURCE)); continue }
            if (translation.isEmpty()) {
                result += PlannedRow(line, null, RowAction.Error(RowError.EMPTY_TRANSLATION, source)); continue
            }
            val fileType = if (typeCell.isEmpty()) null else TypeValues.parse(typeCell)
            if (typeCell.isNotEmpty() && fileType == null) {
                result += PlannedRow(line, null, RowAction.Error(RowError.UNKNOWN_TYPE, typeCell)); continue
            }
            val type = fileType ?: options.defaultType
            if (type == null) { result += PlannedRow(line, null, RowAction.Error(RowError.NO_TYPE, source)); continue }

            val tags = m.tags?.let { parseTags(cells.getOrElse(it) { "" }) }.orEmpty()
            val row = ParsedRow(line, source, translation, type, fileType != null, tags)
            val key = row.key

            if (options.policy == DuplicatePolicy.IMPORT_AS_NEW) {
                result += PlannedRow(line, row, RowAction.New); continue
            }

            val earlier = firstInFile[key]
            if (earlier != null) {
                if (options.policy == DuplicatePolicy.SKIP) {
                    result += PlannedRow(line, row, RowAction.Skip(SkipReason.DUPLICATE_IN_FILE))
                } else {
                    // UPDATE: последняя строка файла побеждает, теги объединяются.
                    val prev = result[earlier]
                    val prevData = prev.data!!
                    result[earlier] = prev.copy(
                        data = prevData.copy(
                            translation = row.translation,
                            type = if (row.typeFromFile) row.type else prevData.type,
                            typeFromFile = prevData.typeFromFile || row.typeFromFile,
                            tags = mergeTags(prevData.tags, row.tags),
                        ),
                    )
                    result += PlannedRow(line, row, RowAction.Skip(SkipReason.MERGED_IN_FILE))
                }
                continue
            }

            val existingId = existing[key]
            firstInFile[key] = result.size
            result += when {
                existingId == null -> PlannedRow(line, row, RowAction.New)
                options.policy == DuplicatePolicy.SKIP -> PlannedRow(line, row, RowAction.Skip(SkipReason.DUPLICATE_IN_LIBRARY))
                else -> PlannedRow(line, row, RowAction.Update(existingId))
            }
        }
        return ImportPlan(result)
    }

    /** "кухня; быт;;TOPIK3" → [кухня, быт, TOPIK3] без повторов (без учёта регистра). */
    fun parseTags(cell: String): List<String> =
        mergeTags(emptyList(), cell.split(';').map { TextKeys.clean(it) }.filter { it.isNotEmpty() })

    private fun mergeTags(a: List<String>, b: List<String>): List<String> {
        val seen = HashSet<String>()
        return (a + b).filter { seen.add(TextKeys.key(it)) }
    }
}
