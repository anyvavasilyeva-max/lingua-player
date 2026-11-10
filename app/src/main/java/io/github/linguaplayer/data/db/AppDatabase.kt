package io.github.linguaplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.linguaplayer.R
import io.github.linguaplayer.util.TextKeys

/**
 * Локальная база данных.
 *
 * ПРАВИЛО: при любом изменении сущностей увеличить [version] и добавить Migration
 * (или AutoMigration). Схемы каждой версии сохраняются в app/schemas/ и коммитятся в git.
 * fallbackToDestructiveMigration НЕ используется — данные пользователя нельзя терять.
 */
@Database(
    entities = [
        LanguagePairEntity::class,
        EntryEntity::class,
        TagEntity::class,
        EntryTagCrossRef::class,
        PlaylistEntity::class,
        PlaylistEntryCrossRef::class,
        SmartPlaylistTagCrossRef::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun languagePairDao(): LanguagePairDao
    abstract fun entryDao(): EntryDao
    abstract fun tagDao(): TagDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val FILE_NAME = "lingua_player.db"

        /** v1 → v2: обложки плейлистов. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN cover_path TEXT")
            }
        }

        /**
         * v2 → v3: у каждого плейлиста теперь есть языковая пара (библиотеки пар не пересекаются).
         * Плейлистам без пары назначается пара, к которой относится большинство их записей,
         * а пустым — первая пара. Схема таблиц не меняется — только данные.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE playlists SET pair_id = COALESCE(" +
                        "(SELECT e.pair_id FROM playlist_entries pe JOIN entries e ON e.id = pe.entry_id " +
                        "WHERE pe.playlist_id = playlists.id GROUP BY e.pair_id ORDER BY COUNT(*) DESC, e.pair_id LIMIT 1), " +
                        "(SELECT MIN(id) FROM language_pairs)) WHERE pair_id IS NULL",
                )
            }
        }

        /** Все миграции схемы перечисляются здесь. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context, inMemory: Boolean = false): AppDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
            }
            return builder
                .addMigrations(*MIGRATIONS)
                .addCallback(SeedCallback(context.getString(R.string.tag_dont_remember)))
                .build()
        }
    }

    /**
     * Начальные данные: языковая пара корейский → русский и системный тег «Не помню».
     * Тег дополнительно проверяется при каждом открытии БД (INSERT OR IGNORE по уникальному system_key).
     */
    private class SeedCallback(private val dontRememberName: String) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO language_pairs (source_lang, target_lang, created_at) VALUES (?, ?, ?)",
                arrayOf<Any>("ko-KR", "ru-RU", System.currentTimeMillis()),
            )
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO tags (name, name_key, system_key, created_at) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(
                    dontRememberName,
                    TextKeys.key(dontRememberName),
                    TagEntity.SYSTEM_DONT_REMEMBER,
                    System.currentTimeMillis(),
                ),
            )
        }
    }
}
