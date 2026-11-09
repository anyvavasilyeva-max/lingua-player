package io.github.linguaplayer.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Данные пользователя переживают обновление схемы БД.
 *
 * База старой версии создаётся ровно по экспортированной схеме (app/schemas/…/N.json),
 * затем открывается текущим Room с миграциями. Room при открытии сверяет фактическую схему
 * с ожидаемой и падает, если миграция написана неверно.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun createFromSchema(version: Int, name: String): SQLiteDatabase {
        val json = JSONObject(File("schemas/io.github.linguaplayer.data.db.AppDatabase/$version.json").readText())
        val database = json.getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = database.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    @Test
    fun migrate1To2KeepsDataAndAddsCoverColumn() {
        val name = "migration-test.db"
        createFromSchema(1, name).apply {
            execSQL("INSERT INTO language_pairs (id, source_lang, target_lang, created_at) VALUES (1, 'ko-KR', 'ru-RU', 0)")
            execSQL(
                "INSERT INTO entries (id, pair_id, source, translation, type, source_key, translation_key, created_at, updated_at) " +
                    "VALUES (1, 1, '포크', 'Вилка', 'word', '포크', 'вилка', 0, 0)",
            )
            execSQL("INSERT INTO tags (id, name, name_key, system_key, created_at) VALUES (1, 'Не помню', 'не помню', 'dont_remember', 0)")
            execSQL("INSERT INTO entry_tags (entry_id, tag_id) VALUES (1, 1)")
            execSQL("INSERT INTO playlists (id, name, kind, smart_type, smart_tag_mode, created_at, updated_at) VALUES (1, 'Кухня', 'normal', 'all', 'any', 0, 0)")
            execSQL("INSERT INTO playlist_entries (playlist_id, entry_id, position) VALUES (1, 1, 0)")
            close()
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        runBlocking {
            val p = db.playlistDao().getById(1)!!
            assertEquals("Кухня", p.name)
            assertNull(p.coverPath)
            assertEquals(listOf(1L), db.playlistDao().getEntryIdsOrdered(1))
            assertEquals("포크", db.entryDao().getById(1)!!.source)
            assertEquals(listOf(1L), db.entryDao().getTagIds(1))
        }
        assertEquals(2, db.openHelper.readableDatabase.version)
        db.close()
    }
}
