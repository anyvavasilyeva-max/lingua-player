package io.github.linguaplayer.data.repo

import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.data.db.TagWithCount
import io.github.linguaplayer.util.TextKeys
import kotlinx.coroutines.flow.Flow

class TagRepository(
    db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.tagDao()

    fun observeAll(): Flow<List<TagEntity>> = dao.observeAll()

    /** Теги, используемые в паре (и системные). */
    fun observeUsedInPair(pairId: Long): Flow<List<TagEntity>> = dao.observeUsedInPair(pairId)

    fun observeWithCounts(): Flow<List<TagWithCount>> = dao.observeAllWithCounts()

    suspend fun dontRememberTag(): TagEntity? = dao.getBySystemKey(TagEntity.SYSTEM_DONT_REMEMBER)

    suspend fun create(name: String): TagEntity {
        val clean = TextKeys.clean(name)
        if (clean.isEmpty()) throw UserFacingException(UserFacingException.Reason.EMPTY_FIELD)
        val key = TextKeys.key(clean)
        if (dao.getByNameKey(key) != null) throw UserFacingException(UserFacingException.Reason.TAG_EXISTS)
        val tag = TagEntity(name = clean, nameKey = key, createdAt = clock())
        return tag.copy(id = dao.insert(tag))
    }

    /** Найти тег по имени (без учёта регистра) или создать новый. Используется импортом. */
    suspend fun getOrCreate(name: String): TagEntity? {
        val clean = TextKeys.clean(name)
        if (clean.isEmpty()) return null
        return dao.getByNameKey(TextKeys.key(clean)) ?: create(clean)
    }

    /** Переименование разрешено и для системных тегов: приложение находит их по system_key. */
    suspend fun rename(id: Long, newName: String) {
        val clean = TextKeys.clean(newName)
        if (clean.isEmpty()) throw UserFacingException(UserFacingException.Reason.EMPTY_FIELD)
        val key = TextKeys.key(clean)
        val existing = dao.getByNameKey(key)
        if (existing != null && existing.id != id) {
            throw UserFacingException(UserFacingException.Reason.TAG_EXISTS)
        }
        dao.rename(id, clean, key)
    }

    /** Удаляет тег (связи с записями удаляются каскадно, сами записи остаются). */
    suspend fun delete(id: Long) {
        val tag = dao.getById(id) ?: return
        if (tag.isSystem) throw UserFacingException(UserFacingException.Reason.SYSTEM_TAG)
        dao.deleteUserTag(id)
    }
}
