package com.elmotuisk.ytd.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ytd_preferences")

data class UserPreferences(
    val lastPlatform: Platform = Platform.YOUTUBE,
    val lastFormat: DownloadFormat = DownloadFormat.VIDEO_MP4,
    val lastQuality: String = "Best",
)

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val platformKey = stringPreferencesKey("last_platform")
    private val formatKey = stringPreferencesKey("last_format")
    private val qualityKey = stringPreferencesKey("last_quality")

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            lastPlatform = prefs[platformKey]?.let {
                try { Platform.valueOf(it) } catch (_: Exception) { Platform.YOUTUBE }
            } ?: Platform.YOUTUBE,
            lastFormat = prefs[formatKey]?.let {
                try { DownloadFormat.valueOf(it) } catch (_: Exception) { DownloadFormat.VIDEO_MP4 }
            } ?: DownloadFormat.VIDEO_MP4,
            lastQuality = prefs[qualityKey] ?: "Best",
        )
    }

    suspend fun savePlatform(platform: Platform) {
        context.dataStore.edit { it[platformKey] = platform.name }
    }

    suspend fun saveFormat(format: DownloadFormat) {
        context.dataStore.edit { it[formatKey] = format.name }
    }

    suspend fun saveQuality(quality: String) {
        context.dataStore.edit { it[qualityKey] = quality }
    }
}
