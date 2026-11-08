package io.github.linguaplayer

import android.content.Context
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.data.repo.EntryRepository
import io.github.linguaplayer.data.repo.LanguagePairRepository
import io.github.linguaplayer.data.repo.PlaylistRepository
import io.github.linguaplayer.data.repo.SettingsRepository
import io.github.linguaplayer.data.repo.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Ручное внедрение зависимостей: один экземпляр каждого компонента на приложение.
 * Без Hilt/Koin — для проекта такого размера это проще и прозрачнее.
 */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.build(context)
    val entries = EntryRepository(database)
    val tags = TagRepository(database)
    val playlists = PlaylistRepository(database, entries)
    val languagePairs = LanguagePairRepository(database)
    val settings = SettingsRepository(context)

    /** Активная языковая пара: выбранная в настройках, иначе первая из существующих. */
    val activePair: Flow<LanguagePairEntity?> =
        combine(languagePairs.observeAll(), settings.activePairId) { pairs, id ->
            pairs.firstOrNull { it.id == id } ?: pairs.firstOrNull()
        }
}
