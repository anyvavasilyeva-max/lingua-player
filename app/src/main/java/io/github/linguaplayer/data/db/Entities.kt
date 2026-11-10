package io.github.linguaplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Языковая пара: изучаемый язык → язык перевода. Коды — BCP 47 (ko-KR, ru-RU, en-US…).
 */
@Entity(
    tableName = "language_pairs",
    indices = [Index(value = ["source_lang", "target_lang"], unique = true)],
)
data class LanguagePairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Словарная запись. Несколько переводов хранятся в одной строке [translation]
 * (например, «вилка; вилочка»).
 *
 * [sourceKey] и [translationKey] — нормализованные копии текста (см. TextKeys)
 * для поиска без учёта регистра и для обнаружения дубликатов при импорте.
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = LanguagePairEntity::class,
            parentColumns = ["id"],
            childColumns = ["pair_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["pair_id", "source_key"]),
        Index(value = ["type"]),
    ],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pair_id") val pairId: Long,
    val source: String,
    val translation: String,
    val type: EntryType,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "translation_key") val translationKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Тег. [systemKey] != null у системных тегов (например, «Не помню»): приложение находит их
 * по этому ключу, поэтому пользователь может свободно переименовывать тег.
 * [nameKey] — нормализованное имя; уникально, чтобы не было «Кухня» и «кухня» одновременно.
 */
@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["name_key"], unique = true),
        Index(value = ["system_key"], unique = true),
    ],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "system_key") val systemKey: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    val isSystem: Boolean get() = systemKey != null

    companion object {
        /** Стабильный внутренний идентификатор системного тега «Не помню». */
        const val SYSTEM_DONT_REMEMBER = "dont_remember"
    }
}

@Entity(
    tableName = "entry_tags",
    primaryKeys = ["entry_id", "tag_id"],
    foreignKeys = [
        ForeignKey(EntryEntity::class, ["id"], ["entry_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["tag_id"])],
)
data class EntryTagCrossRef(
    @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

/**
 * Плейлист. Принадлежит одной языковой паре [pairId] (с версии схемы 3 задаётся всегда;
 * плейлисты и библиотеки разных пар не пересекаются). Для умного плейлиста ([kind] = SMART)
 * содержимое задают поля smart_*: тип записей и теги (таблица smart_playlist_tags).
 */
@Entity(
    tableName = "playlists",
    foreignKeys = [
        ForeignKey(LanguagePairEntity::class, ["id"], ["pair_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["pair_id"])],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: PlaylistKind,
    @ColumnInfo(name = "pair_id") val pairId: Long? = null,
    @ColumnInfo(name = "smart_type") val smartType: TypeFilter = TypeFilter.ALL,
    @ColumnInfo(name = "smart_tag_mode") val smartTagMode: TagMatchMode = TagMatchMode.ANY,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
    /** Путь к файлу обложки во внутренней памяти приложения; null — обложка из названия. Схема v2. */
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
)

/**
 * Запись в обычном плейлисте. Удаление плейлиста удаляет только эти связи (CASCADE),
 * сами словарные записи остаются в библиотеке.
 */
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlist_id", "entry_id"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(EntryEntity::class, ["id"], ["entry_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["entry_id"]), Index(value = ["playlist_id", "position"])],
)
data class PlaylistEntryCrossRef(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "entry_id") val entryId: Long,
    val position: Int,
)

@Entity(
    tableName = "smart_playlist_tags",
    primaryKeys = ["playlist_id", "tag_id"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["tag_id"])],
)
data class SmartPlaylistTagCrossRef(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)
