package io.github.linguaplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface LanguagePairDao {
    @Query("SELECT * FROM language_pairs ORDER BY id")
    fun observeAll(): Flow<List<LanguagePairEntity>>

    @Query("SELECT * FROM language_pairs ORDER BY id")
    suspend fun getAll(): List<LanguagePairEntity>

    @Query("SELECT * FROM language_pairs WHERE id = :id")
    suspend fun getById(id: Long): LanguagePairEntity?

    @Query("SELECT * FROM language_pairs WHERE source_lang = :source AND target_lang = :target")
    suspend fun find(source: String, target: String): LanguagePairEntity?

    @Insert
    suspend fun insert(pair: LanguagePairEntity): Long

    @Query("SELECT COUNT(*) FROM entries WHERE pair_id = :pairId")
    suspend fun countEntries(pairId: Long): Int

    @Query("DELETE FROM language_pairs WHERE id = :id")
    suspend fun delete(id: Long)
}

data class EntryKey(
    val id: Long,
    @ColumnInfo(name = "source_key") val sourceKey: String,
)

@Dao
interface EntryDao {
    @Insert
    suspend fun insert(entry: EntryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<EntryEntity>): List<Long>

    @Update
    suspend fun update(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getById(id: Long): EntryEntity?

    @Query("SELECT * FROM entries WHERE pair_id = :pairId AND source_key = :sourceKey ORDER BY id")
    suspend fun findBySourceKey(pairId: Long, sourceKey: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<EntryEntity>

    /** Ключи всех записей пары — для поиска дубликатов при импорте. */
    @Query("SELECT id, source_key FROM entries WHERE pair_id = :pairId ORDER BY id")
    suspend fun getKeys(pairId: Long): List<EntryKey>

    @Query("SELECT COUNT(*) FROM entries")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM entries WHERE pair_id = :pairId")
    fun observeCountForPair(pairId: Long): Flow<Int>

    @RawQuery(observedEntities = [EntryEntity::class, EntryTagCrossRef::class, PlaylistEntryCrossRef::class])
    fun observeQuery(query: SupportSQLiteQuery): Flow<List<EntryEntity>>

    @RawQuery
    suspend fun query(query: SupportSQLiteQuery): List<EntryEntity>

    // --- Теги записей ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagLinks(links: List<EntryTagCrossRef>)

    @Query("DELETE FROM entry_tags WHERE entry_id = :entryId AND tag_id = :tagId")
    suspend fun deleteTagLink(entryId: Long, tagId: Long)

    @Query("DELETE FROM entry_tags WHERE entry_id IN (:entryIds) AND tag_id IN (:tagIds)")
    suspend fun deleteTagLinks(entryIds: List<Long>, tagIds: List<Long>)

    @Query("DELETE FROM entry_tags WHERE entry_id = :entryId")
    suspend fun clearTags(entryId: Long)

    @Query("SELECT tag_id FROM entry_tags WHERE entry_id = :entryId")
    suspend fun getTagIds(entryId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM entry_tags WHERE entry_id = :entryId AND tag_id = :tagId")
    suspend fun hasTag(entryId: Long, tagId: Long): Int

    @Query(
        "SELECT COUNT(*) > 0 FROM entry_tags et JOIN tags t ON t.id = et.tag_id " +
            "WHERE et.entry_id = :entryId AND t.system_key = :systemKey",
    )
    fun observeHasSystemTag(entryId: Long, systemKey: String): Flow<Boolean>

    @Query("SELECT * FROM entry_tags")
    fun observeAllTagLinks(): Flow<List<EntryTagCrossRef>>
}

data class TagWithCount(
    @Embedded val tag: TagEntity,
    @ColumnInfo(name = "usage_count") val usageCount: Int,
)

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY (system_key IS NULL), name_key")
    fun observeAll(): Flow<List<TagEntity>>

    @Query(
        "SELECT t.*, (SELECT COUNT(*) FROM entry_tags et WHERE et.tag_id = t.id) AS usage_count " +
            "FROM tags t ORDER BY (t.system_key IS NULL), t.name_key",
    )
    fun observeAllWithCounts(): Flow<List<TagWithCount>>

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name_key = :nameKey")
    suspend fun getByNameKey(nameKey: String): TagEntity?

    @Query("SELECT * FROM tags WHERE system_key = :systemKey")
    suspend fun getBySystemKey(systemKey: String): TagEntity?

    @Insert
    suspend fun insert(tag: TagEntity): Long

    @Query("UPDATE tags SET name = :name, name_key = :nameKey WHERE id = :id")
    suspend fun rename(id: Long, name: String, nameKey: String)

    /** Системные теги удалить нельзя — условие в самом запросе. */
    @Query("DELETE FROM tags WHERE id = :id AND system_key IS NULL")
    suspend fun deleteUserTag(id: Long): Int
}

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    /** Для обычных плейлистов — число добавленных записей; для умных всегда 0 (считается отдельно). */
    @ColumnInfo(name = "entry_count") val entryCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM playlist_entries pe WHERE pe.playlist_id = p.id) AS entry_count " +
            "FROM playlists p ORDER BY p.name COLLATE NOCASE",
    )
    fun observeAllWithCounts(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM playlist_entries pe WHERE pe.playlist_id = p.id) AS entry_count " +
            "FROM playlists p WHERE p.last_opened_at IS NOT NULL ORDER BY p.last_opened_at DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE kind = 'normal' ORDER BY name COLLATE NOCASE")
    suspend fun getNormalPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET last_opened_at = :time WHERE id = :id")
    suspend fun markOpened(id: Long, time: Long)

    // --- Состав обычного плейлиста ---

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(refs: List<PlaylistEntryCrossRef>)

    @Query("DELETE FROM playlist_entries WHERE playlist_id = :playlistId AND entry_id IN (:entryIds)")
    suspend fun removeEntries(playlistId: Long, entryIds: List<Long>)

    @Query("SELECT entry_id FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position, entry_id")
    suspend fun getEntryIdsOrdered(playlistId: Long): List<Long>

    @Query("UPDATE playlist_entries SET position = :position WHERE playlist_id = :playlistId AND entry_id = :entryId")
    suspend fun setPosition(playlistId: Long, entryId: Long, position: Int)

    @Query("SELECT playlist_id FROM playlist_entries WHERE entry_id = :entryId")
    suspend fun getPlaylistIdsForEntry(entryId: Long): List<Long>

    // --- Фильтры умного плейлиста ---

    @Query("SELECT tag_id FROM smart_playlist_tags WHERE playlist_id = :playlistId")
    suspend fun getSmartTagIds(playlistId: Long): List<Long>

    @Query("SELECT tag_id FROM smart_playlist_tags WHERE playlist_id = :playlistId")
    fun observeSmartTagIds(playlistId: Long): Flow<List<Long>>

    @Query("DELETE FROM smart_playlist_tags WHERE playlist_id = :playlistId")
    suspend fun clearSmartTags(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSmartTags(refs: List<SmartPlaylistTagCrossRef>)
}
