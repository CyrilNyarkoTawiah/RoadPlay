package com.roadplay.player.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore by preferencesDataStore(name = "roadplay_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val SPEED = floatPreferencesKey("default_speed")
        val RESUME = booleanPreferencesKey("resume_playback")
        val AUTO_SUBS = booleanPreferencesKey("auto_subtitles")
        val SUB_SCALE = floatPreferencesKey("subtitle_scale")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASC = booleanPreferencesKey("sort_ascending")
        val CAR_HIDE_SECONDS = intPreferencesKey("car_controls_hide_seconds")
        val CAR_FONT_SCALE = floatPreferencesKey("car_font_scale")
        val CAR_DRIVE_VIDEO = booleanPreferencesKey("car_video_while_driving")
        val BROWSER_HOME = stringPreferencesKey("browser_home_url")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = this[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            defaultSpeed = this[Keys.SPEED] ?: d.defaultSpeed,
            resumePlayback = this[Keys.RESUME] ?: d.resumePlayback,
            autoSubtitles = this[Keys.AUTO_SUBS] ?: d.autoSubtitles,
            subtitleScale = this[Keys.SUB_SCALE] ?: d.subtitleScale,
            sortField = this[Keys.SORT_FIELD]?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: d.sortField,
            sortAscending = this[Keys.SORT_ASC] ?: d.sortAscending,
            carControlsHideSeconds = this[Keys.CAR_HIDE_SECONDS] ?: d.carControlsHideSeconds,
            carFontScale = this[Keys.CAR_FONT_SCALE] ?: d.carFontScale,
            videoWhileDriving = this[Keys.CAR_DRIVE_VIDEO] ?: d.videoWhileDriving,
            browserHomeUrl = this[Keys.BROWSER_HOME] ?: d.browserHomeUrl,
        )
    }

    suspend fun setTheme(mode: ThemeMode) { context.settingsStore.edit { it[Keys.THEME] = mode.name } }
    suspend fun setDefaultSpeed(speed: Float) { context.settingsStore.edit { it[Keys.SPEED] = speed } }
    suspend fun setResume(enabled: Boolean) { context.settingsStore.edit { it[Keys.RESUME] = enabled } }
    suspend fun setAutoSubtitles(enabled: Boolean) { context.settingsStore.edit { it[Keys.AUTO_SUBS] = enabled } }
    suspend fun setSubtitleScale(scale: Float) { context.settingsStore.edit { it[Keys.SUB_SCALE] = scale } }
    suspend fun setCarControlsHideSeconds(seconds: Int) {
        context.settingsStore.edit { it[Keys.CAR_HIDE_SECONDS] = seconds.coerceIn(1, 10) }
    }
    suspend fun setCarFontScale(scale: Float) {
        context.settingsStore.edit { it[Keys.CAR_FONT_SCALE] = scale.coerceIn(0.8f, 1.6f) }
    }
    suspend fun setVideoWhileDriving(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.CAR_DRIVE_VIDEO] = enabled }
    }
    suspend fun setBrowserHomeUrl(url: String) {
        context.settingsStore.edit { it[Keys.BROWSER_HOME] = url.trim() }
    }
    suspend fun setSort(field: SortField, ascending: Boolean) {
        context.settingsStore.edit { it[Keys.SORT_FIELD] = field.name; it[Keys.SORT_ASC] = ascending }
    }
}
