package io.github.linguaplayer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Простые настройки приложения (DataStore). Параметры плеера добавятся на этапе 3. */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val ACTIVE_PAIR = longPreferencesKey("active_pair_id")
    }

    val activePairId: Flow<Long?> = context.dataStore.data.map { it[Keys.ACTIVE_PAIR] }

    suspend fun setActivePair(id: Long) {
        context.dataStore.edit { it[Keys.ACTIVE_PAIR] = id }
    }
}
