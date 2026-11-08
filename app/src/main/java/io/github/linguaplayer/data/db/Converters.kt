package io.github.linguaplayer.data.db

import androidx.room.TypeConverter

/** Перечисления хранятся строковыми кодами, а не порядковыми номерами: так безопаснее при изменениях. */
class Converters {
    @TypeConverter fun entryTypeToCode(v: EntryType): String = v.code
    @TypeConverter fun codeToEntryType(v: String): EntryType = EntryType.fromCode(v) ?: EntryType.WORD

    @TypeConverter fun typeFilterToCode(v: TypeFilter): String = v.code
    @TypeConverter fun codeToTypeFilter(v: String): TypeFilter = TypeFilter.fromCode(v)

    @TypeConverter fun tagModeToCode(v: TagMatchMode): String = v.code
    @TypeConverter fun codeToTagMode(v: String): TagMatchMode = TagMatchMode.fromCode(v)

    @TypeConverter fun playlistKindToCode(v: PlaylistKind): String = v.code
    @TypeConverter fun codeToPlaylistKind(v: String): PlaylistKind = PlaylistKind.fromCode(v)
}
