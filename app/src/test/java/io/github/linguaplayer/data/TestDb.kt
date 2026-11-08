package io.github.linguaplayer.data

import androidx.test.core.app.ApplicationProvider
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.repo.EntryRepository
import io.github.linguaplayer.data.repo.LanguagePairRepository
import io.github.linguaplayer.data.repo.PlaylistRepository
import io.github.linguaplayer.data.repo.TagRepository

/** Набор репозиториев поверх in-memory БД для тестов. */
class TestDb {
    private var time = 1_000L
    val clock: () -> Long = { time++ }
    val db: AppDatabase = AppDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
    val entries = EntryRepository(db, clock)
    val tags = TagRepository(db, clock)
    val playlists = PlaylistRepository(db, entries, clock)
    val pairs = LanguagePairRepository(db, clock)

    fun close() = db.close()
}
