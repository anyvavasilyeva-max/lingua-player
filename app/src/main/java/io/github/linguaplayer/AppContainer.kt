package io.github.linguaplayer

import android.content.Context
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.data.repo.EntryRepository
import io.github.linguaplayer.data.repo.CoverStore
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.player.AndroidTtsEngine
import io.github.linguaplayer.player.PlaybackSource
import io.github.linguaplayer.player.PlayerEngine
import io.github.linguaplayer.playback.PlaybackGuard
import io.github.linguaplayer.playback.PlaybackStateStore
import io.github.linguaplayer.playback.SavedPlayback
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.io.File
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
    val covers = CoverStore(context)
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

    /** Сохранённая очередь и позиция (восстанавливаются после закрытия приложения). */
    val playbackStore = PlaybackStateStore(File(context.filesDir, "playback/queue.json"))

    val player: PlayerEngine by lazy {
        PlayerEngine(mainScope, playerSpeechOverride ?: speech).also { p ->
            PlaybackGuard(context, p, mainScope)
            mainScope.launch {
                restorePlayback(p)
                persistPlayback(p)
            }
            mainScope.launch { settings.playback.collect { p.updateSettings(it) } }
            // Библиотеки пар не пересекаются: при переключении пары очередь другой пары сбрасывается.
            mainScope.launch {
                activePair.map { it?.id }.distinctUntilChanged().collect { id ->
                    if (p.state.value.queue.any { it.pairId != id }) p.clear()
                }
            }
            if (playerSpeechOverride == null) {
                mainScope.launch { settings.ttsEngine.collect { speech.setEnginePackage(it) } }
            }
        }
    }

    /** Восстанавливает сохранённую очередь (на паузе), если она относится к активной паре. */
    private suspend fun restorePlayback(p: PlayerEngine) {
        val saved = withContext(Dispatchers.IO) { playbackStore.load() } ?: return
        if (p.state.value.hasQueue) return
        p.updateSettings(settings.playback.first())
        val active = activePair.first() ?: return
        val byId = saved.sourceIds.chunked(500).flatMap { database.entryDao().getByIds(it) }.associateBy { it.id }
        val entries = saved.sourceIds.mapNotNull { byId[it] }.filter { it.pairId == active.id }
        if (entries.isEmpty() || p.state.value.hasQueue) return
        val pairs = languagePairs.getAll().associateBy { it.id }
        p.restore(PlaybackSource(saved.title, entries, pairs, saved.playlistId), saved.order, saved.index)
    }

    /** Сохраняет очередь при её смене и позицию при переходе к другой записи. */
    @OptIn(FlowPreview::class)
    private suspend fun persistPlayback(p: PlayerEngine) {
        p.state
            .map { st -> Triple(System.identityHashCode(st.queue), st.index, st.title) }
            .distinctUntilChanged()
            .debounce(500)
            .collect {
                val st = p.state.value
                val saved = if (!st.hasQueue) null else SavedPlayback(
                    title = st.title,
                    playlistId = st.playlistId,
                    sourceIds = p.currentSource()?.entries?.map { it.id }.orEmpty(),
                    order = st.queue.map { it.entryId to it.sourceFirst },
                    index = st.index,
                )
                withContext(Dispatchers.IO) { runCatching { playbackStore.save(saved) } }
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
