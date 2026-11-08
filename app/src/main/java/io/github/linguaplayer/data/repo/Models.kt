package io.github.linguaplayer.data.repo

import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.data.db.TagEntity
import java.util.Locale

/** Запись вместе с тегами — то, что показывается в списках. */
data class EntryWithTags(
    val entry: EntryEntity,
    val tags: List<TagEntity>,
)

/** Данные формы редактирования записи. id = 0 — новая запись. */
data class EntryDraft(
    val id: Long = 0,
    val pairId: Long,
    val source: String,
    val translation: String,
    val type: EntryType,
    val tagIds: Set<Long>,
    /** null — не трогать членство в плейлистах. */
    val playlistIds: Set<Long>? = null,
)

data class EntryDetails(
    val entry: EntryEntity,
    val tagIds: Set<Long>,
    val playlistIds: Set<Long>,
)

/** Ошибки, о которых нужно понятно сообщить пользователю. */
class UserFacingException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { EMPTY_FIELD, TAG_EXISTS, SYSTEM_TAG, PAIR_EXISTS, PAIR_INVALID, PAIR_IN_USE, NOT_FOUND }
}

/** Название языка для интерфейса, например «корейский (Южная Корея)» → «корейский». */
fun languageDisplayName(tag: String, uiLocale: Locale = Locale.getDefault()): String {
    val locale = Locale.forLanguageTag(tag)
    val name = locale.getDisplayLanguage(uiLocale)
    return if (name.isNullOrBlank()) tag else name.replaceFirstChar { it.titlecase(uiLocale) }
}

fun LanguagePairEntity.displayName(uiLocale: Locale = Locale.getDefault()): String =
    "${languageDisplayName(sourceLang, uiLocale)} → ${languageDisplayName(targetLang, uiLocale)}"
