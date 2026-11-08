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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

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
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
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
            sourceRate = this[Keys.SOURCE_RATE] ?: d.sourceRate,
            targetRate = this[Keys.TARGET_RATE] ?: d.targetRate,
            translationEnabled = this[Keys.TRANSLATION] ?: d.translationEnabled,
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
        }
    }

    /** Пакет движка синтеза речи; null — системный по умолчанию. */
    val ttsEngine: Flow<String?> = context.dataStore.data.map { it[Keys.TTS_ENGINE] }.distinctUntilChanged()

    suspend fun setTtsEngine(pkg: String?) {
        context.dataStore.edit { if (pkg == null) it.remove(Keys.TTS_ENGINE) else it[Keys.TTS_ENGINE] = pkg }
    }
}
