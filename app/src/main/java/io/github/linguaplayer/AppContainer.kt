package io.github.linguaplayer

import android.content.Context
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.data.repo.EntryRepository
import io.github.linguaplayer.data.repo.ImportRepository
import io.github.linguaplayer.data.repo.LanguagePairRepository
import io.github.linguaplayer.data.repo.PlaylistRepository
import io.github.linguaplayer.data.repo.SettingsRepository
import io.github.linguaplayer.data.repo.TagRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.player.AndroidTtsEngine
import io.github.linguaplayer.player.PlaybackSource
import io.github.linguaplayer.player.PlayerEngine
import io.github.linguaplayer.player.SpeechEngine
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.combine

/**
 * Ручное внедрение зависимостей: один экземпляр каждого компонента на приложение.
 * Без Hilt/Koin — для проекта такого размера это проще и прозрачнее.
 */
class AppContainer(private val context: Context) {
    val database: AppDatabase = AppDatabase.build(context)
    val entries = EntryRepository(database)
    val tags = TagRepository(database)
    val playlists = PlaylistRepository(database, entries)
    val languagePairs = LanguagePairRepository(database)
    val importer = ImportRepository(database, tags)

    /** Область для долгих операций (импорт), которые не должны прерываться при уходе с экрана. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Главный поток: здесь живёт плеер (как и MediaSession на этапе 4). */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Синтезатор речи создаётся при первом обращении (запуск TTS занимает время). */
    val speech: AndroidTtsEngine by lazy { AndroidTtsEngine(context) }

    /** Только для тестов: подменить синтезатор плеера до первого обращения к [player]. */
    @VisibleForTesting
    var playerSpeechOverride: SpeechEngine? = null

    val player: PlayerEngine by lazy {
        PlayerEngine(mainScope, playerSpeechOverride ?: speech).also { p ->
            mainScope.launch { settings.playback.collect { p.updateSettings(it) } }
            if (playerSpeechOverride == null) {
                mainScope.launch { settings.ttsEngine.collect { speech.setEnginePackage(it) } }
            }
        }
    }

    /** Запуск воспроизведения набора записей (плейлист, библиотека, выборка). */
    suspend fun startPlayback(title: String, entries: List<EntryEntity>, playlistId: Long? = null, startEntryId: Long? = null) {
        val pairs = languagePairs.getAll().associateBy { it.id }
        val p = player
        p.updateSettings(settings.playback.first())
        p.load(PlaybackSource(title, entries, pairs, playlistId), startEntryId)
        playlistId?.let { playlists.markOpened(it) }
    }
    val settings = SettingsRepository(context)

    /** Активная языковая пара: выбранная в настройках, иначе первая из существующих. */
    val activePair: Flow<LanguagePairEntity?> =
        combine(languagePairs.observeAll(), settings.activePairId) { pairs, id ->
            pairs.firstOrNull { it.id == id } ?: pairs.firstOrNull()
        }
}
