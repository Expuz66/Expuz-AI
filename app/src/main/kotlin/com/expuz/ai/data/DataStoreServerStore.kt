package com.expuz.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.expuzDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "expuz_settings"
)

class DataStoreServerStore(private val context: Context) : ServerStore {

    override val config: Flow<ServerConfig> = context.expuzDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toServerConfig() }
        .distinctUntilChanged()

    override suspend fun current(): ServerConfig = config.first()

    override suspend fun save(config: ServerConfig) {
        context.expuzDataStore.edit { preferences ->
            preferences[Keys.ACTIVE_URL] = config.activeServerUrl
            preferences[Keys.LOCAL_URL] = config.localUrl
            preferences[Keys.REMOTE_URL] = config.remoteUrl
            preferences[Keys.MODE] = config.mode.name
            preferences[Keys.ONBOARDING_COMPLETED] = config.onboardingCompleted
        }
    }

    private fun Preferences.toServerConfig() = ServerConfig(
        activeServerUrl = this[Keys.ACTIVE_URL].orEmpty(),
        localUrl = this[Keys.LOCAL_URL].orEmpty(),
        remoteUrl = this[Keys.REMOTE_URL].orEmpty(),
        mode = ConnectionMode.fromStorage(this[Keys.MODE]),
        onboardingCompleted = this[Keys.ONBOARDING_COMPLETED] ?: false,
    )

    private object Keys {
        val ACTIVE_URL = stringPreferencesKey("active_server_url")
        val LOCAL_URL = stringPreferencesKey("local_server_url")
        val REMOTE_URL = stringPreferencesKey("remote_server_url")
        val MODE = stringPreferencesKey("connection_mode")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
