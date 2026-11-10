package io.github.linguaplayer.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
    fun migrate1To3KeepsDataAndAddsCoverColumn() {
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
            assertEquals(1L, p.pairId) // v2 → v3: плейлисту назначена пара его записей
            assertEquals(listOf(1L), db.playlistDao().getEntryIdsOrdered(1))
            assertEquals("포크", db.entryDao().getById(1)!!.source)
            assertEquals(listOf(1L), db.entryDao().getTagIds(1))
        }
        assertEquals(3, db.openHelper.readableDatabase.version)
        db.close()
    }

    @Test
    fun migrate2To3AssignsPairsToPlaylists() {
        val name = "migration-2-3.db"
        createFromSchema(2, name).apply {
            execSQL("INSERT INTO language_pairs (id, source_lang, target_lang, created_at) VALUES (1, 'ko-KR', 'ru-RU', 0)")
            execSQL("INSERT INTO language_pairs (id, source_lang, target_lang, created_at) VALUES (2, 'en-US', 'ru-RU', 0)")
            fun entry(id: Int, pair: Int, src: String) = execSQL(
                "INSERT INTO entries (id, pair_id, source, translation, type, source_key, translation_key, created_at, updated_at) " +
                    "VALUES ($id, $pair, '$src', 'x', 'word', '$src', 'x', 0, 0)",
            )
            entry(1, 1, "포크"); entry(2, 2, "fork"); entry(3, 2, "spoon"); entry(4, 1, "숟가락")
            fun playlist(id: Int, pair: String) = execSQL(
                "INSERT INTO playlists (id, name, kind, pair_id, smart_type, smart_tag_mode, created_at, updated_at) " +
                    "VALUES ($id, 'P$id', 'normal', $pair, 'all', 'any', 0, 0)",
            )
            playlist(1, "NULL"); playlist(2, "NULL"); playlist(3, "2"); playlist(4, "NULL")
            execSQL("INSERT INTO playlist_entries VALUES (1, 1, 0)")                      // P1: корейские
            execSQL("INSERT INTO playlist_entries VALUES (1, 4, 1)")
            execSQL("INSERT INTO playlist_entries VALUES (4, 2, 0)")                      // P4: 2 англ. + 1 кор.
            execSQL("INSERT INTO playlist_entries VALUES (4, 3, 1)")
            execSQL("INSERT INTO playlist_entries VALUES (4, 1, 2)")
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
        runBlocking {
            val dao = db.playlistDao()
            assertEquals(1L, dao.getById(1)!!.pairId) // по записям
            assertEquals(1L, dao.getById(2)!!.pairId) // пустой → первая пара
            assertEquals(2L, dao.getById(3)!!.pairId) // уже была пара — не меняется
            assertEquals(2L, dao.getById(4)!!.pairId) // большинство записей — английские
            // Связи не удаляются (данные не теряются), но чужая запись не считается и не показывается.
            assertEquals(3, dao.getEntryIdsOrdered(4).size)
            assertEquals(2, dao.observeAllWithCounts(2).first().single { it.playlist.id == 4L }.entryCount)
        }
        db.close()
    }
}
