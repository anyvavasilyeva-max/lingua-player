package io.github.linguaplayer.data.db

/** Тип словарной записи. Значение [code] хранится в БД и используется в CSV/XLSX. */
enum class EntryType(val code: String) {
    WORD("word"),
    PHRASE("phrase");

    companion object {
        fun fromCode(code: String): EntryType? =
            entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
    }
}

/** Фильтр по типу записей: все / только слова / только фразы. */
enum class TypeFilter(val code: String) {
    ALL("all"), WORDS("word"), PHRASES("phrase");

    companion object {
        fun fromCode(code: String): TypeFilter = entries.firstOrNull { it.code == code } ?: ALL
    }
}

/** Как сочетаются несколько выбранных тегов. */
enum class TagMatchMode(val code: String) {
    /** Запись имеет хотя бы один из выбранных тегов. */
    ANY("any"),
    /** Запись имеет все выбранные теги. */
    ALL("all");

    companion object {
        fun fromCode(code: String): TagMatchMode = entries.firstOrNull { it.code == code } ?: ANY
    }
}

enum class PlaylistKind(val code: String) {
    /** Записи добавляются вручную, порядок задаёт пользователь. */
    NORMAL("normal"),
    /** Содержимое вычисляется по фильтрам (теги, тип, языковая пара). */
    SMART("smart");

    companion object {
        fun fromCode(code: String): PlaylistKind = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}
