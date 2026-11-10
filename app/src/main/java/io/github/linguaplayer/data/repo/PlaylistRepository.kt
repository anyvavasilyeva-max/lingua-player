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

/** Фильтры умного плейлиста. Языковая пара — всегда пара самого плейлиста. */
data class SmartRules(
    val type: TypeFilter,
    val tagIds: Set<Long>,
    val tagMode: TagMatchMode,
)

class PlaylistRepository(
    private val db: AppDatabase,
    private val entries: EntryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Удаление файла обложки (в тестах — подмена). */
    private val deleteFile: (String) -> Unit = { java.io.File(it).delete() },
) {
    private val dao = db.playlistDao()

    /** Плейлисты только указанной языковой пары. */
    fun observeAll(pairId: Long): Flow<List<PlaylistWithCount>> = dao.observeAllWithCounts(pairId)

    fun observeRecent(pairId: Long, limit: Int = 5): Flow<List<PlaylistWithCount>> = dao.observeRecent(pairId, limit)

    fun observe(id: Long): Flow<PlaylistEntity?> = dao.observeById(id)

    suspend fun get(id: Long): PlaylistEntity? = dao.getById(id)

    suspend fun normalPlaylists(pairId: Long): List<PlaylistEntity> = dao.getNormalPlaylists(pairId)

    suspend fun smartTagIds(id: Long): Set<Long> = dao.getSmartTagIds(id).toSet()

    /** Плейлист всегда создаётся в языковой паре [pairId]. */
    suspend fun create(name: String, pairId: Long, kind: PlaylistKind = PlaylistKind.NORMAL, rules: SmartRules? = null): Long {
        val clean = TextKeys.clean(name)
        if (clean.isEmpty()) throw UserFacingException(UserFacingException.Reason.EMPTY_FIELD)
        val now = clock()
        return db.withTransaction {
            val id = dao.insert(
                PlaylistEntity(
                    name = clean,
                    kind = kind,
                    pairId = pairId,
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
            dao.update(p.copy(smartType = rules.type, smartTagMode = rules.tagMode, updatedAt = clock()))
            dao.clearSmartTags(id)
            dao.insertSmartTags(rules.tagIds.map { SmartPlaylistTagCrossRef(id, it) })
        }
    }

    /** Удаляет плейлист (и файл обложки). Словарные записи остаются в библиотеке. */
    suspend fun delete(id: Long) {
        val cover = dao.getById(id)?.coverPath
        dao.delete(id)
        cover?.let(deleteFile)
    }

    /** Ставит (или убирает, если null) обложку. Старый файл удаляется. */
    suspend fun setCover(id: Long, path: String?) {
        val old = dao.getById(id)?.coverPath
        dao.setCover(id, path, clock())
        if (old != null && old != path) deleteFile(old)
    }

    suspend fun markOpened(id: Long) = dao.markOpened(id, clock())

    /**
     * Добавляет записи в конец обычного плейлиста; уже присутствующие пропускаются.
     * Записи другой языковой пары не добавляются — библиотеки пар не пересекаются.
     */
    suspend fun addEntries(playlistId: Long, entryIds: List<Long>) {
        db.withTransaction {
            val pairId = dao.getById(playlistId)?.pairId ?: return@withTransaction
            val allowed = entryIds.distinct().chunked(500).flatMap { db.entryDao().idsInPair(it, pairId) }.toHashSet()
            val existing = dao.getEntryIdsOrdered(playlistId).toHashSet()
            var pos = dao.maxPosition(playlistId) + 1
            val refs = entryIds.distinct().filter { it in allowed && it !in existing }.map { PlaylistEntryCrossRef(playlistId, it, pos++) }
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
        PlaylistKind.NORMAL -> EntryFilter(pairId = playlist.pairId ?: -1, playlistId = playlist.id, sort = EntrySort.PLAYLIST_ORDER)
        PlaylistKind.SMART -> EntryFilter(
            pairId = playlist.pairId ?: -1,
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
                        entries.observeEntries(EntryFilter(pairId = p.pairId ?: -1, playlistId = p.id, sort = EntrySort.PLAYLIST_ORDER))
                    else -> entries.observeEntries(
                        EntryFilter(pairId = p.pairId ?: -1, type = p.smartType, tagIds = tagIds.toSet(), tagMode = p.smartTagMode),
                    )
                }
            }
}
