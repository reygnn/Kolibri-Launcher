package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM over [FakeDataStore]: defaults on an empty store, per-field
 * save→read round-trips, and the defensive enum decode — a corrupt / unknown
 * persisted enum name must fall back to the default instead of throwing.
 */
class NyxWallpaperDisplaySettingsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------------------------------------------------------------- defaults
    @Test
    fun empty_store_yields_all_defaults() = runTest(mainDispatcherRule.dispatcher) {
        val settings = NyxWallpaperDisplaySettings(FakeDataStore())

        assertThat(settings.wallpaperScrimAlphaStateFlow.first())
            .isEqualTo(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)
        assertThat(settings.wallpaperSurfaceModeFlow.first()).isEqualTo(WallpaperSurfaceMode.AUTO)
        assertThat(settings.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)
    }

    // ---------------------------------------------------------------- round-trips
    @Test
    fun scrim_alpha_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val settings = NyxWallpaperDisplaySettings(FakeDataStore())

        settings.setWallpaperScrimAlpha(0.42f)

        assertThat(settings.wallpaperScrimAlphaStateFlow.first()).isEqualTo(0.42f)
    }

    @Test
    fun surface_mode_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val settings = NyxWallpaperDisplaySettings(FakeDataStore())

        settings.setWallpaperSurfaceMode(WallpaperSurfaceMode.DARK)

        assertThat(settings.wallpaperSurfaceModeFlow.first()).isEqualTo(WallpaperSurfaceMode.DARK)
    }

    @Test
    fun backdrop_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val settings = NyxWallpaperDisplaySettings(FakeDataStore())

        settings.setWallpaperBackdrop(WallpaperBackdrop.BLACK)

        assertThat(settings.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.BLACK)
    }

    // ---------------------------------------------------------------- defensive decode
    @Test
    fun corrupt_surface_mode_falls_back_to_default() = runTest(mainDispatcherRule.dispatcher) {
        val store = FakeDataStore()
        store.edit { it[stringPreferencesKey("wallpaper_surface_mode")] = "NOT_A_MODE" }
        val settings = NyxWallpaperDisplaySettings(store)

        assertThat(settings.wallpaperSurfaceModeFlow.first()).isEqualTo(WallpaperSurfaceMode.AUTO)
    }

    @Test
    fun corrupt_backdrop_falls_back_to_default() = runTest(mainDispatcherRule.dispatcher) {
        val store = FakeDataStore()
        store.edit { it[stringPreferencesKey("wallpaper_backdrop")] = "" }
        val settings = NyxWallpaperDisplaySettings(store)

        assertThat(settings.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)
    }

    @Test
    fun valid_persisted_enum_name_decodes() = runTest(mainDispatcherRule.dispatcher) {
        val store = FakeDataStore()
        store.edit {
            it[stringPreferencesKey("wallpaper_surface_mode")] = WallpaperSurfaceMode.LIGHT.name
            it[floatPreferencesKey("wallpaper_scrim_alpha")] = 1.0f
        }
        val settings = NyxWallpaperDisplaySettings(store)

        assertThat(settings.wallpaperSurfaceModeFlow.first()).isEqualTo(WallpaperSurfaceMode.LIGHT)
        assertThat(settings.wallpaperScrimAlphaStateFlow.first()).isEqualTo(1.0f)
    }
}
