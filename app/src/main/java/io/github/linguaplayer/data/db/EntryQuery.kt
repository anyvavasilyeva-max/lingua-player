package io.github.linguaplayer.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.linguaplayer.util.TextKeys

enum class EntrySort { ALPHABETICAL, NEWEST, PLAYLIST_ORDER }

/**
 * Универсальный фильтр записей. Используется библиотекой, обычными плейлистами
 * (через [playlistId]) и умными плейлистами (через теги / тип / пару).
 */
data class EntryFilter(
    val pairId: Long? = null,
    val text: String = "",
    val type: TypeFilter = TypeFilter.ALL,
    val tagIds: Set<Long> = emptySet(),
    val tagMode: TagMatchMode = TagMatchMode.ANY,
    /** Если задан — только записи этого обычного плейлиста. */
    val playlistId: Long? = null,
    val sort: EntrySort = EntrySort.ALPHABETICAL,
)

/** Строит SQL для [EntryFilter]. Все значения передаются параметрами (никакой конкатенации ввода). */
object EntryQueryBuilder {

    fun build(filter: EntryFilter): SimpleSQLiteQuery {
        val sql = StringBuilder("SELECT e.* FROM entries e")
        val args = mutableListOf<Any>()

        if (filter.playlistId != null) {
            sql.append(" JOIN playlist_entries pe ON pe.entry_id = e.id AND pe.playlist_id = ?")
            args += filter.playlistId
        }
        sql.append(" WHERE 1 = 1")

        filter.pairId?.let {
            sql.append(" AND e.pair_id = ?")
            args += it
        }
        when (filter.type) {
            TypeFilter.ALL -> Unit
            TypeFilter.WORDS -> { sql.append(" AND e.type = ?"); args += EntryType.WORD.code }
            TypeFilter.PHRASES -> { sql.append(" AND e.type = ?"); args += EntryType.PHRASE.code }
        }
        val text = TextKeys.key(filter.text)
        if (text.isNotEmpty()) {
            // instr() вместо LIKE: не нужно экранировать % и _, работает с любым Unicode.
            sql.append(" AND (instr(e.source_key, ?) > 0 OR instr(e.translation_key, ?) > 0)")
            args += text
            args += text
        }
        if (filter.tagIds.isNotEmpty()) {
            val placeholders = filter.tagIds.joinToString(",") { "?" }
            when (filter.tagMode) {
                TagMatchMode.ANY ->
                    sql.append(" AND e.id IN (SELECT entry_id FROM entry_tags WHERE tag_id IN ($placeholders))")
                TagMatchMode.ALL ->
                    sql.append(
                        " AND e.id IN (SELECT entry_id FROM entry_tags WHERE tag_id IN ($placeholders)" +
                            " GROUP BY entry_id HAVING COUNT(DISTINCT tag_id) = ${filter.tagIds.size})",
                    )
            }
            args.addAll(filter.tagIds)
        }

        val order = when {
            filter.sort == EntrySort.PLAYLIST_ORDER && filter.playlistId != null -> "pe.position, e.id"
            filter.sort == EntrySort.NEWEST -> "e.created_at DESC, e.id DESC"
            else -> "e.source_key, e.id"
        }
        sql.append(" ORDER BY ").append(order)
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}
