package io.github.linguaplayer.data.repo

import androidx.room.withTransaction
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntrySort
import io.github.linguaplayer.data.db.PlaylistEntity
import io.github.linguaplayer.data.db.PlaylistEntryCrossRef
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.data.db.PlaylistWithCount
import io.github.linguaplayer.data.db.SmartPlaylistTagCrossRef
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.util.TextKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Описание фильтров умного плейлиста. */
data class SmartRules(
    val pairId: Long?,
    val type: TypeFilter,
    val tagIds: Set<Long>,
    val tagMode: TagMatchMode,
)

class PlaylistRepository(
    private val db: AppDatabase,
    private val entries: EntryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.playlistDao()

    fun observeAll(): Flow<List<PlaylistWithCount>> = dao.observeAllWithCounts()

    fun observeRecent(limit: Int = 5): Flow<List<PlaylistWithCount>> = dao.observeRecent(limit)

    fun observe(id: Long): Flow<PlaylistEntity?> = dao.observeById(id)

    suspend fun get(id: Long): PlaylistEntity? = dao.getById(id)

    suspend fun normalPlaylists(): List<PlaylistEntity> = dao.getNormalPlaylists()

    suspend fun smartTagIds(id: Long): Set<Long> = dao.getSmartTagIds(id).toSet()

    suspend fun create(name: String, kind: PlaylistKind = PlaylistKind.NORMAL, rules: SmartRules? = null): Long {
        val clean = TextKeys.clean(name)
        if (clean.isEmpty()) throw UserFacingException(UserFacingException.Reason.EMPTY_FIELD)
        val now = clock()
        return db.withTransaction {
            val id = dao.insert(
                PlaylistEntity(
                    name = clean,
                    kind = kind,
                    pairId = rules?.pairId,
                    smartType = rules?.type ?: TypeFilter.ALL,
                    smartTagMode = rules?.tagMode ?: TagMatchMode.ANY,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (kind == PlaylistKind.SMART && rules != null) {
                dao.insertSmartTags(rules.tagIds.map { SmartPlaylistTagCrossRef(id, it) })
            }
            id
        }
    }

    suspend fun rename(id: Long, name: String) {
        val clean = TextKeys.clean(name)
        if (clean.isEmpty()) throw UserFacingException(UserFacingException.Reason.EMPTY_FIELD)
        val p = dao.getById(id) ?: throw UserFacingException(UserFacingException.Reason.NOT_FOUND)
        dao.update(p.copy(name = clean, updatedAt = clock()))
    }

    suspend fun updateSmartRules(id: Long, rules: SmartRules) {
        db.withTransaction {
            val p = dao.getById(id) ?: throw UserFacingException(UserFacingException.Reason.NOT_FOUND)
            dao.update(
                p.copy(pairId = rules.pairId, smartType = rules.type, smartTagMode = rules.tagMode, updatedAt = clock()),
            )
            dao.clearSmartTags(id)
            dao.insertSmartTags(rules.tagIds.map { SmartPlaylistTagCrossRef(id, it) })
        }
    }

    /** Удаляет плейлист. Словарные записи остаются в библиотеке. */
    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun markOpened(id: Long) = dao.markOpened(id, clock())

    /** Добавляет записи в конец обычного плейлиста; уже присутствующие пропускаются. */
    suspend fun addEntries(playlistId: Long, entryIds: List<Long>) {
        db.withTransaction {
            val existing = dao.getEntryIdsOrdered(playlistId).toHashSet()
            var pos = dao.maxPosition(playlistId) + 1
            val refs = entryIds.distinct().filter { it !in existing }.map { PlaylistEntryCrossRef(playlistId, it, pos++) }
            dao.insertEntries(refs)
        }
    }

    suspend fun removeEntries(playlistId: Long, entryIds: List<Long>) {
        db.withTransaction { entryIds.chunked(500).forEach { dao.removeEntries(playlistId, it) } }
    }

    /** Ручная сортировка: перемещает запись с позиции [from] на позицию [to] и перенумеровывает список. */
    suspend fun move(playlistId: Long, from: Int, to: Int) {
        db.withTransaction {
            val ids = dao.getEntryIdsOrdered(playlistId).toMutableList()
            if (from !in ids.indices || to !in ids.indices || from == to) return@withTransaction
            ids.add(to, ids.removeAt(from))
            ids.forEachIndexed { index, entryId -> dao.setPosition(playlistId, entryId, index) }
        }
    }

    /** Фильтр, описывающий содержимое плейлиста (обычного или умного). */
    suspend fun filterFor(playlist: PlaylistEntity): EntryFilter = when (playlist.kind) {
        PlaylistKind.NORMAL -> EntryFilter(playlistId = playlist.id, sort = EntrySort.PLAYLIST_ORDER)
        PlaylistKind.SMART -> EntryFilter(
            pairId = playlist.pairId,
            type = playlist.smartType,
            tagIds = dao.getSmartTagIds(playlist.id).toSet(),
            tagMode = playlist.smartTagMode,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeEntries(playlistId: Long): Flow<List<EntryWithTags>> =
        combine(dao.observeById(playlistId), dao.observeSmartTagIds(playlistId)) { p, tagIds -> p to tagIds }
            .flatMapLatest { (p, tagIds) ->
                when {
                    p == null -> flowOf(emptyList())
                    p.kind == PlaylistKind.NORMAL ->
                        entries.observeEntries(EntryFilter(playlistId = p.id, sort = EntrySort.PLAYLIST_ORDER))
                    else -> entries.observeEntries(
                        EntryFilter(pairId = p.pairId, type = p.smartType, tagIds = tagIds.toSet(), tagMode = p.smartTagMode),
                    )
                }
            }
}
