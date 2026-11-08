package io.github.linguaplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun languagePairDao(): LanguagePairDao
    abstract fun entryDao(): EntryDao
    abstract fun tagDao(): TagDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val FILE_NAME = "lingua_player.db"

        /** Все миграции схемы перечисляются здесь. */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()

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
