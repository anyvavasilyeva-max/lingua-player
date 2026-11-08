package io.github.linguaplayer.data.repo

import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.LanguagePairEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale

class LanguagePairRepository(
    db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.languagePairDao()

    fun observeAll(): Flow<List<LanguagePairEntity>> = dao.observeAll()

    suspend fun getAll(): List<LanguagePairEntity> = dao.getAll()

    suspend fun get(id: Long): LanguagePairEntity? = dao.getById(id)

    suspend fun add(sourceTag: String, targetTag: String): Long {
        val source = normalizeTag(sourceTag)
        val target = normalizeTag(targetTag)
        if (source == target) throw UserFacingException(UserFacingException.Reason.PAIR_INVALID)
        if (dao.find(source, target) != null) throw UserFacingException(UserFacingException.Reason.PAIR_EXISTS)
        return dao.insert(LanguagePairEntity(sourceLang = source, targetLang = target, createdAt = clock()))
    }

    /** Пару можно удалить, только если в ней нет записей. */
    suspend fun delete(id: Long) {
        if (dao.countEntries(id) > 0) throw UserFacingException(UserFacingException.Reason.PAIR_IN_USE)
        dao.delete(id)
    }

    companion object {
        /** Проверяет и приводит код BCP 47 к канонической форме: "ko_kr" → "ko-KR". */
        fun normalizeTag(tag: String): String {
            val canonical = Locale.forLanguageTag(tag.trim().replace('_', '-')).toLanguageTag()
            if (canonical == "und" || canonical.isBlank()) {
                throw UserFacingException(UserFacingException.Reason.PAIR_INVALID)
            }
            return canonical
        }
    }
}
