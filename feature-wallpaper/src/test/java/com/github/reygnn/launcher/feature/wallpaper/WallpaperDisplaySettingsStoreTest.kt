package com.github.reygnn.launcher.feature.wallpaper

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * [WallpaperDisplaySettingsStore] (3a-4): per-app key names, Kolibri's read and write behaviour,
 * the purge of exactly its three keys, and the keep-list ownership the gate cannot see by name.
 */
class WallpaperDisplaySettingsStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Kolibri's names, the old surface name included (D3).
    private val keys = WallpaperDisplayKeys(scrimAlpha = "wallpaper_scrim_alpha", backdrop = "wallpaper_backdrop", surfaceMode = "app_drawer_mode")

    @Test
    fun a_fresh_store_reads_the_defaults() = runTest(mainDispatcherRule.testDispatcher) {
        val store = WallpaperDisplaySettingsStore(InMemoryPreferencesStore(), keys)

        assertThat(store.wallpaperScrimAlphaStateFlow.first()).isEqualTo(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)
        assertThat(store.wallpaperSurfaceModeFlow.first()).isEqualTo(WallpaperSurfaceMode.AUTO)
        assertThat(store.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)
    }

    @Test
    fun values_are_written_under_the_apps_own_key_names() = runTest(mainDispatcherRule.testDispatcher) {
        val data = InMemoryPreferencesStore()
        val store = WallpaperDisplaySettingsStore(data, keys)

        store.setWallpaperScrimAlpha(0.4f)
        store.setWallpaperSurfaceMode(WallpaperSurfaceMode.DARK)
        store.setWallpaperBackdrop(WallpaperBackdrop.BLACK)

        assertThat(data.state.value[floatPreferencesKey("wallpaper_scrim_alpha")]).isEqualTo(0.4f)
        assertThat(data.state.value[stringPreferencesKey("app_drawer_mode")]).isEqualTo("DARK") // no migration
        assertThat(data.state.value[stringPreferencesKey("wallpaper_backdrop")]).isEqualTo("BLACK")
        assertThat(store.wallpaperSurfaceModeFlow.first()).isEqualTo(WallpaperSurfaceMode.DARK)
    }

    @Test
    fun an_unknown_enum_name_reads_as_the_default() = runTest(mainDispatcherRule.testDispatcher) {
        val data = InMemoryPreferencesStore(preferencesOf(stringPreferencesKey("app_drawer_mode") to "NO_SUCH_MODE"))

        assertThat(WallpaperDisplaySettingsStore(data, keys).wallpaperSurfaceModeFlow.first()).isEqualTo(WallpaperSurfaceMode.AUTO)
    }

    @Test
    fun a_read_error_falls_back_to_the_defaults_like_kolibris_settings() = runTest(mainDispatcherRule.testDispatcher) {
        val data = InMemoryPreferencesStore().apply { readFailure = IOException("corrupt file") }

        assertThat(WallpaperDisplaySettingsStore(data, keys).wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)
    }

    @Test
    fun a_write_error_is_swallowed_like_kolibris_settings() = runTest(mainDispatcherRule.testDispatcher) {
        val data = InMemoryPreferencesStore().apply { writeFailure = IOException("disk full") }

        WallpaperDisplaySettingsStore(data, keys).setWallpaperScrimAlpha(0.4f) // no exception (D4)

        assertThat(data.state.value.asMap()).isEmpty()
    }

    @Test
    fun the_purge_removes_exactly_its_three_keys() = runTest(mainDispatcherRule.testDispatcher) {
        val other = stringPreferencesKey("someone_elses_key")
        val data = InMemoryPreferencesStore(
            mutablePreferencesOf(
                floatPreferencesKey("wallpaper_scrim_alpha") to 0.4f,
                stringPreferencesKey("wallpaper_backdrop") to "BLACK",
                stringPreferencesKey("app_drawer_mode") to "DARK",
                other to "stays",
            ),
        )

        WallpaperDisplaySettingsStore(data, keys).purgeRepository()

        assertThat(data.state.value.asMap().keys.map { it.name }).containsExactly("someone_elses_key")
    }

    @Test
    fun a_failing_purge_is_reported() = runTest(mainDispatcherRule.testDispatcher) {
        // F1: the reset must see a store that could not be purged.
        val data = InMemoryPreferencesStore().apply { writeFailure = IOException("disk full") }

        assertFailsWith<IOException> { WallpaperDisplaySettingsStore(data, keys).purgeRepository() }
    }

    // ---- backdrop toggle (3a-9b) ----

    @Test
    fun toggle_flips_the_persisted_backdrop() = runTest(mainDispatcherRule.testDispatcher) {
        val data = InMemoryPreferencesStore(preferencesOf(stringPreferencesKey("wallpaper_backdrop") to "BLACK"))
        val store = WallpaperDisplaySettingsStore(data, keys)

        store.toggleBackdrop()

        assertThat(store.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)
    }

    @Test
    fun a_double_toggle_nets_to_a_no_op() = runTest(mainDispatcherRule.testDispatcher) {
        val store = WallpaperDisplaySettingsStore(InMemoryPreferencesStore(), keys)

        store.toggleBackdrop()
        store.toggleBackdrop()

        assertThat(store.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)
    }

    @Test
    fun a_failed_write_is_retried_with_the_same_target_and_never_thrown() = runTest(mainDispatcherRule.testDispatcher) {
        // The setters swallow failures (D4); the toggle does too, but it does not advance its
        // last-written value — so the next tap aims at the same target again.
        val data = InMemoryPreferencesStore().apply { writeFailure = java.io.IOException("disk full") }
        val store = WallpaperDisplaySettingsStore(data, keys)

        store.toggleBackdrop() // no exception reaches the caller
        assertThat(store.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)

        data.writeFailure = null
        store.toggleBackdrop()

        assertThat(store.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.BLACK)
    }

    @Test
    fun it_owns_exactly_its_three_configured_names() {
        // The keep-list gate sees that the store writes keys, not each name (they come from the
        // configuration) — this pins them.
        val store = WallpaperDisplaySettingsStore(InMemoryPreferencesStore(), keys)

        assertThat(store.ownedExactKeys()).containsExactly("wallpaper_scrim_alpha", "wallpaper_backdrop", "app_drawer_mode")
    }
}
