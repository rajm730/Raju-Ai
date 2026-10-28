package com.raju.aiassistant.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raju.aiassistant.domain.ai.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "raju_settings")

/** Non-secret user preferences. Secrets go to [com.raju.aiassistant.core.security.SecureKeyStore]. */
data class UserSettings(
    val defaultProvider: ProviderId = ProviderId.GEMINI,
    val lowPowerMode: Boolean = false,
    val reducedMotion: Boolean = false,
    val saveHistory: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<UserSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            UserSettings(
                defaultProvider = p[Keys.DEFAULT_PROVIDER]
                    ?.let { name -> ProviderId.entries.firstOrNull { it.name == name } }
                    ?: ProviderId.GEMINI,
                lowPowerMode = p[Keys.LOW_POWER] ?: false,
                reducedMotion = p[Keys.REDUCED_MOTION] ?: false,
                saveHistory = p[Keys.SAVE_HISTORY] ?: true,
            )
        }

    suspend fun setDefaultProvider(provider: ProviderId) =
        context.settingsDataStore.edit { it[Keys.DEFAULT_PROVIDER] = provider.name }

    suspend fun setLowPowerMode(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.LOW_POWER] = enabled }

    suspend fun setReducedMotion(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.REDUCED_MOTION] = enabled }

    suspend fun setSaveHistory(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.SAVE_HISTORY] = enabled }

    private object Keys {
        val DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
        val LOW_POWER = booleanPreferencesKey("low_power_mode")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
    }
}
