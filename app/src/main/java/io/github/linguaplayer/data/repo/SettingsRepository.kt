package io.github.linguaplayer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.player.Direction
import io.github.linguaplayer.player.PlayOrder
import io.github.linguaplayer.player.PlaybackSettings
import io.github.linguaplayer.player.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AutoBackupSettings(
    val enabled: Boolean = false,
    /** Файл автокопии (content:// URI с постоянным доступом), например на Google Диске. */
    val targetUri: String? = null,
    val targetName: String? = null,
    val lastAt: Long? = null,
    val lastError: String? = null,
)

/** Цвет оформления по умолчанию — #00B2D1. */
const val DEFAULT_SEED_COLOR: Int = 0xFF00B2D1.toInt()

/** Настройки приложения и плеера (DataStore). */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val ACTIVE_PAIR = longPreferencesKey("active_pair_id")
        val DIRECTION = stringPreferencesKey("play_direction")
        val TYPE = stringPreferencesKey("play_type")
        val ORDER = stringPreferencesKey("play_order")
        val PAUSE = intPreferencesKey("play_pause_seconds")
        val REPEATS = intPreferencesKey("play_repeats")
        val SOURCE_RATE = floatPreferencesKey("play_source_rate")
        val TARGET_RATE = floatPreferencesKey("play_target_rate")
        val TRANSLATION = booleanPreferencesKey("play_translation")
        val REPEAT_MODE = stringPreferencesKey("play_repeat_mode")
        val SLOW_REPEAT = booleanPreferencesKey("play_slow_repeat")
        val HIDE_ANSWER = booleanPreferencesKey("play_hide_answer")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val SEED_COLOR = intPreferencesKey("ui_seed_color")
        val PLAYLISTS_GRID = booleanPreferencesKey("ui_playlists_grid")
        val AUTO_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_TARGET = stringPreferencesKey("auto_backup_target_uri")
        val AUTO_TARGET_NAME = stringPreferencesKey("auto_backup_target_name")
        val AUTO_LAST_AT = longPreferencesKey("auto_backup_last_at")
        val AUTO_LAST_ERROR = stringPreferencesKey("auto_backup_last_error")
    }

    val activePairId: Flow<Long?> = context.dataStore.data.map { it[Keys.ACTIVE_PAIR] }

    suspend fun setActivePair(id: Long) {
        context.dataStore.edit { it[Keys.ACTIVE_PAIR] = id }
    }

    val playback: Flow<PlaybackSettings> = context.dataStore.data.map { it.toPlayback() }.distinctUntilChanged()

    private fun Preferences.toPlayback(): PlaybackSettings {
        val d = PlaybackSettings()
        return PlaybackSettings(
            direction = Direction.fromCode(this[Keys.DIRECTION]),
            typeFilter = this[Keys.TYPE]?.let(TypeFilter::fromCode) ?: d.typeFilter,
            order = PlayOrder.fromCode(this[Keys.ORDER]),
            pauseSeconds = this[Keys.PAUSE]?.takeIf { it in PlaybackSettings.PAUSES } ?: d.pauseSeconds,
            repeats = this[Keys.REPEATS]?.takeIf { it in PlaybackSettings.REPEATS } ?: d.repeats,
            sourceRate = this[Keys.SOURCE_RATE]?.takeIf { it in PlaybackSettings.RATES } ?: d.sourceRate,
            targetRate = this[Keys.TARGET_RATE]?.takeIf { it in PlaybackSettings.RATES } ?: d.targetRate,
            translationEnabled = this[Keys.TRANSLATION] ?: d.translationEnabled,
            repeatMode = RepeatMode.fromCode(this[Keys.REPEAT_MODE]),
            slowRepeat = this[Keys.SLOW_REPEAT] ?: d.slowRepeat,
            hideAnswer = this[Keys.HIDE_ANSWER] ?: d.hideAnswer,
        )
    }

    suspend fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toPlayback())
            prefs[Keys.DIRECTION] = s.direction.code
            prefs[Keys.TYPE] = s.typeFilter.code
            prefs[Keys.ORDER] = s.order.code
            prefs[Keys.PAUSE] = s.pauseSeconds
            prefs[Keys.REPEATS] = s.repeats
            prefs[Keys.SOURCE_RATE] = s.sourceRate
            prefs[Keys.TARGET_RATE] = s.targetRate
            prefs[Keys.TRANSLATION] = s.translationEnabled
            prefs[Keys.REPEAT_MODE] = s.repeatMode.code
            prefs[Keys.SLOW_REPEAT] = s.slowRepeat
            prefs[Keys.HIDE_ANSWER] = s.hideAnswer
        }
    }

    /** Основной цвет оформления (ARGB). Из него строится вся цветовая схема. */
    val seedColor: Flow<Int> = context.dataStore.data.map { it[Keys.SEED_COLOR] ?: DEFAULT_SEED_COLOR }.distinctUntilChanged()

    suspend fun setSeedColor(argb: Int) {
        context.dataStore.edit { it[Keys.SEED_COLOR] = argb }
    }

    /** Плейлисты плиткой (true) или списком. */
    val playlistsGrid: Flow<Boolean> = context.dataStore.data.map { it[Keys.PLAYLISTS_GRID] ?: true }.distinctUntilChanged()

    suspend fun setPlaylistsGrid(grid: Boolean) {
        context.dataStore.edit { it[Keys.PLAYLISTS_GRID] = grid }
    }

    /** Настройки и состояние автоматической резервной копии. */
    val autoBackup: Flow<AutoBackupSettings> = context.dataStore.data.map {
        AutoBackupSettings(
            enabled = it[Keys.AUTO_ENABLED] ?: false,
            targetUri = it[Keys.AUTO_TARGET],
            targetName = it[Keys.AUTO_TARGET_NAME],
            lastAt = it[Keys.AUTO_LAST_AT],
            lastError = it[Keys.AUTO_LAST_ERROR],
        )
    }.distinctUntilChanged()

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_ENABLED] = enabled }
    }

    suspend fun setAutoBackupTarget(uri: String?, name: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.AUTO_TARGET) else it[Keys.AUTO_TARGET] = uri
            if (name == null) it.remove(Keys.AUTO_TARGET_NAME) else it[Keys.AUTO_TARGET_NAME] = name
            it.remove(Keys.AUTO_LAST_ERROR)
        }
    }

    suspend fun setAutoBackupResult(at: Long?, error: String?) {
        context.dataStore.edit {
            if (at != null) it[Keys.AUTO_LAST_AT] = at
            if (error == null) it.remove(Keys.AUTO_LAST_ERROR) else it[Keys.AUTO_LAST_ERROR] = error
        }
    }

    /** Пакет движка синтеза речи; null — системный по умолчанию. */
    val ttsEngine: Flow<String?> = context.dataStore.data.map { it[Keys.TTS_ENGINE] }.distinctUntilChanged()

    suspend fun setTtsEngine(pkg: String?) {
        context.dataStore.edit { if (pkg == null) it.remove(Keys.TTS_ENGINE) else it[Keys.TTS_ENGINE] = pkg }
    }
}
