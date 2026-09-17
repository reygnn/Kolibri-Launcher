package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.common.data.readFlowFailOpen
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's small adapter onto the shared [WallpaperDisplaySettings] port
 * (WALLPAPER_SHARE_SPEC §5, WSS-INV-4): scrim alpha, backdrop and drawer
 * surface mode, persisted in Nyx's single `home_layout` DataStore. Kolibri
 * implements the same port directly on its fat SettingsRepository; Nyx keeps it
 * separate from [PreferencesRepository] so the wallpaper keys stay self-contained.
 *
 * Enums are stored by [Enum.name] and parsed defensively (an unknown / corrupt
 * value falls back to the default rather than throwing).
 */
@Singleton
class NyxWallpaperDisplaySettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : WallpaperDisplaySettings {

    override val wallpaperScrimAlphaStateFlow: Flow<Float> =
        dataStore.readFlowFailOpen("Error reading wallpaper scrim alpha") {
            it[SCRIM_ALPHA] ?: AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA
        }

    override suspend fun setWallpaperScrimAlpha(alpha: Float) {
        dataStore.edit { it[SCRIM_ALPHA] = alpha }
    }

    override val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode> =
        dataStore.readFlowFailOpen("Error reading wallpaper surface mode") { prefs ->
            prefs[SURFACE_MODE].toEnumOr(WallpaperSurfaceMode.AUTO)
        }

    override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) {
        dataStore.edit { it[SURFACE_MODE] = mode.name }
    }

    override val wallpaperBackdropFlow: Flow<WallpaperBackdrop> =
        dataStore.readFlowFailOpen("Error reading wallpaper backdrop") { prefs ->
            prefs[BACKDROP].toEnumOr(WallpaperBackdrop.SYSTEM_WALLPAPER)
        }

    override suspend fun setWallpaperBackdrop(backdrop: WallpaperBackdrop) {
        dataStore.edit { it[BACKDROP] = backdrop.name }
    }

    private companion object {
        val SCRIM_ALPHA = floatPreferencesKey("wallpaper_scrim_alpha")
        val BACKDROP = stringPreferencesKey("wallpaper_backdrop")
        val SURFACE_MODE = stringPreferencesKey("wallpaper_surface_mode")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    if (this == null) default else runCatching { enumValueOf<E>(this) }.getOrDefault(default)
