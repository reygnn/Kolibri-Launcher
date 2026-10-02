package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The per-store purges of Nyx's factory reset (2b-4c, step 3). All six stores share the
 * `home_layout` DataStore, so each test checks two things: the store's own keys are gone, and a
 * foreign key it does not own stays — a purge never clears the whole file.
 */
class NyxStorePurgeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = FakeDataStore()
    private val foreign = stringPreferencesKey("someone_elses_key")
    private val app = ComponentKey.of("com.a", "com.a.Main")
    private val other = ComponentKey.of("com.b", "com.b.Main")

    private suspend fun keys(): Set<String> = store.data.first().asMap().keys.mapTo(HashSet()) { it.name }

    private suspend fun withForeignKey(block: suspend () -> Unit) {
        store.edit { it[foreign] = "stays" }
        block()
        assertThat(keys()).contains(foreign.name)
    }

    @Test
    fun home_layout_purge_removes_the_layout_and_its_seed_flag_together() = runTest(mainDispatcherRule.testDispatcher) {
        withForeignKey {
            val repo = HomeLayoutRepositoryImpl(store, HomeLayoutSerializer(), UuidItemIdFactory())
            repo.seedInitialLayout(resolveDockApps = { listOf(app) }, resolveGridApps = { listOf(other) })
            assertThat(keys()).containsAtLeast("home_layout_v1", "home_dock_seeded_v1")

            repo.purgeRepository()

            assertThat(keys()).containsNoneOf("home_layout_v1", "home_dock_seeded_v1")
        }
    }

    @Test
    fun drawer_folders_purge_removes_the_folders_and_their_seed_flag_together() = runTest(mainDispatcherRule.testDispatcher) {
        withForeignKey {
            val repo = DrawerFoldersRepositoryImpl(store, DrawerFoldersSerializer())
            repo.seedInitialFolders { listOf(DrawerFolder(DrawerFolderId("f1"), "F", listOf(app, other))) }
            assertThat(keys()).containsAtLeast("drawer_folders_v1", "drawer_folders_seeded_v1")

            repo.purgeRepository()

            assertThat(keys()).containsNoneOf("drawer_folders_v1", "drawer_folders_seeded_v1")
        }
    }

    @Test
    fun hidden_apps_purge_removes_its_key() = runTest(mainDispatcherRule.testDispatcher) {
        withForeignKey {
            val repo = HiddenAppsRepositoryImpl(store, HiddenAppsSerializer())
            repo.update { setOf(app) }

            repo.purgeRepository()

            assertThat(keys()).doesNotContain("hidden_apps_v1")
        }
    }

    @Test
    fun preferences_purge_removes_every_key_including_the_legacy_icon_flag() = runTest(mainDispatcherRule.testDispatcher) {
        withForeignKey {
            val repo = PreferencesRepositoryImpl(store)
            repo.setIconStyle(IconStyle.GRAYSCALE)
            repo.setSearchAutoLaunch(true)
            repo.setUsageSortEnabled(true)
            repo.setNotificationDots(true)
            repo.setShowAlarm(true)
            repo.setShowCalendarEvent(true)
            store.edit { it[booleanPreferencesKey("monochrome_icons")] = true } // an upgraded user

            repo.purgeRepository()

            assertThat(keys()).containsNoneOf(
                "icon_style", "monochrome_icons", "search_auto_launch", "drawer_usage_sort",
                "notification_dots", "show_alarm", "show_calendar_event",
            )
        }
    }

    @Test
    fun wallpaper_display_settings_purge_removes_its_keys() = runTest(mainDispatcherRule.testDispatcher) {
        withForeignKey {
            val settings = NyxWallpaperDisplaySettings(store)
            settings.setWallpaperScrimAlpha(0.3f)
            settings.setWallpaperBackdrop(WallpaperBackdrop.BLACK)
            settings.setWallpaperSurfaceMode(WallpaperSurfaceMode.DARK)

            settings.purgeRepository()

            assertThat(keys()).containsNoneOf("wallpaper_scrim_alpha", "wallpaper_backdrop", "wallpaper_surface_mode")
        }
    }

    @Test
    fun fab_position_purge_removes_its_keys() = runTest(mainDispatcherRule.testDispatcher) {
        withForeignKey {
            val fab = NyxFabPositionStore(store)
            fab.saveFabPosition(FabPosition(0.2f, 0.3f))

            fab.purgeRepository()

            assertThat(keys()).containsNoneOf("wallpaper_edit_fab_x_fraction", "wallpaper_edit_fab_y_fraction")
        }
    }
}
