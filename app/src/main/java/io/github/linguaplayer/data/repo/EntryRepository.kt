package io.github.linguaplayer.data.repo

import androidx.room.withTransaction
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntryQueryBuilder
import io.github.linguaplayer.data.db.EntryTagCrossRef
import io.github.linguaplayer.data.db.PlaylistEntryCrossRef
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.util.TextKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class EntryRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entryDao = db.entryDao()
    private val tagDao = db.tagDao()
    private val playlistDao = db.playlistDao()

    fun observeCount(): Flow<Int> = entryDao.observeCount()

    fun observeCountForPair(pairId: Long): Flow<Int> = entryDao.observeCountForPair(pairId)

    /** Записи по фильтру вместе с тегами. Обновляется автоматически при любых изменениях. */
    fun observeEntries(filter: EntryFilter): Flow<List<EntryWithTags>> =
        combine(
            entryDao.observeQuery(EntryQueryBuilder.build(filter)),
            entryDao.observeAllTagLinks(),
            tagDao.observeAll(),
        ) { entries, links, tags -> attachTags(entries, links, tags) }

    suspend fun getEntries(filter: EntryFilter): List<EntryEntity> =
        entryDao.query(EntryQueryBuilder.build(filter))

    suspend fun getDetails(id: Long): EntryDetails? {
        val entry = entryDao.getById(id) ?: return null
        return EntryDetails(
            entry = entry,
            tagIds = entryDao.getTagIds(id).toSet(),
            playlistIds = playlistDao.getPlaylistIdsForEntry(id).toSet(),
        )
    }

    /** Создаёт или обновляет запись вместе с тегами и (опционально) плейлистами. Возвращает id. */
    suspend fun save(draft: EntryDraft): Long {
        val source = TextKeys.clean(draft.source)
        val translation = TextKeys.clean(draft.translation)
        if (source.isEmpty() || translation.isEmpty()) {
            throw UserFacingException(UserFacingException.Reason.EMPTY_FIELD)
        }
        val now = clock()
        return db.withTransaction {
            val id = if (draft.id == 0L) {
                entryDao.insert(
                    EntryEntity(
                        pairId = draft.pairId,
                        source = source,
                        translation = translation,
                        type = draft.type,
                        sourceKey = TextKeys.key(source),
                        translationKey = TextKeys.key(translation),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                val old = entryDao.getById(draft.id)
                    ?: throw UserFacingException(UserFacingException.Reason.NOT_FOUND)
                entryDao.update(
                    old.copy(
                        pairId = draft.pairId,
                        source = source,
                        translation = translation,
                        type = draft.type,
                        sourceKey = TextKeys.key(source),
                        translationKey = TextKeys.key(translation),
                        updatedAt = now,
                    ),
                )
                old.id
            }
            entryDao.clearTags(id)
            entryDao.insertTagLinks(draft.tagIds.map { EntryTagCrossRef(id, it) })
            draft.playlistIds?.let { setPlaylistsForEntry(id, it) }
            id
        }
    }

    private suspend fun setPlaylistsForEntry(entryId: Long, playlistIds: Set<Long>) {
        val current = playlistDao.getPlaylistIdsForEntry(entryId).toSet()
        (current - playlistIds).forEach { playlistDao.removeEntries(it, listOf(entryId)) }
        (playlistIds - current).forEach { playlistId ->
            val pos = playlistDao.maxPosition(playlistId) + 1
            playlistDao.insertEntries(listOf(PlaylistEntryCrossRef(playlistId, entryId, pos)))
        }
    }

    suspend fun delete(ids: Collection<Long>) {
        db.withTransaction {
            // Порциями: на старых Android в одном запросе не более 999 параметров.
            ids.chunked(500).forEach { entryDao.deleteByIds(it) }
        }
    }

    /** Массово добавляет теги выбранным записям (существующие теги сохраняются). */
    suspend fun addTags(entryIds: Collection<Long>, tagIds: Collection<Long>) {
        if (entryIds.isEmpty() || tagIds.isEmpty()) return
        db.withTransaction {
            entryDao.insertTagLinks(entryIds.flatMap { e -> tagIds.map { t -> EntryTagCrossRef(e, t) } })
        }
    }

    /** Переключает тег у записи. Возвращает true, если тег теперь установлен. */
    suspend fun toggleTag(entryId: Long, tagId: Long): Boolean = db.withTransaction {
        if (entryDao.hasTag(entryId, tagId) > 0) {
            entryDao.deleteTagLink(entryId, tagId)
            false
        } else {
            entryDao.insertTagLinks(listOf(EntryTagCrossRef(entryId, tagId)))
            true
        }
    }

    /** Кнопка «Не помню»: тег ищется по системному ключу, а не по имени. */
    suspend fun toggleDontRemember(entryId: Long): Boolean {
        val tag = tagDao.getBySystemKey(TagEntity.SYSTEM_DONT_REMEMBER)
            ?: throw UserFacingException(UserFacingException.Reason.NOT_FOUND)
        return toggleTag(entryId, tag.id)
    }

    companion object {
        fun attachTags(
            entries: List<EntryEntity>,
            links: List<EntryTagCrossRef>,
            tags: List<TagEntity>,
        ): List<EntryWithTags> {
            val tagById = tags.associateBy { it.id }
            val tagsByEntry = links.groupBy({ it.entryId }, { tagById[it.tagId] })
            return entries.map { e ->
                EntryWithTags(e, tagsByEntry[e.id].orEmpty().filterNotNull().sortedBy { it.nameKey })
            }
        }
    }
}
