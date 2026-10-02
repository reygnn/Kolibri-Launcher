package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeAppUsageRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Nyx's factory reset over the `Purgeable` stores (2b-4c, step 3; was `NyxResetManagerTest`,
 * converted, no case dropped): every store purged, a failing store isolated and reported. Plus
 * the guarantee R2 rests on — each data key and its seed flag disappear in the same edit, so
 * seeding after an incomplete reset refills what was emptied and leaves untouched what was not.
 * Robolectric because the real `WallpaperRepositoryImpl` takes part.
 */
@RunWith(RobolectricTestRunner::class)
class ResetRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true) {
        every { clearAll() } returns true // a relaxed Boolean is false: "files left"
    }
    private val usageRepository = FakeAppUsageRepository()
    private val oldApp = ComponentKey.of("com.old", "com.old.Main")
    private val newApp = ComponentKey.of("com.new", "com.new.Main")
    private val other = ComponentKey.of("com.other", "com.other.Main")

    /** A store whose next edit fails once — a purge that fails while later writes (seeding) work. */
    private class FailOnceDataStore(private val inner: FakeDataStore = FakeDataStore()) : DataStore<Preferences> {
        var failNextEdit = false
        override val data = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failNextEdit) {
                failNextEdit = false
                throw IOException("store busy")
            }
            return inner.updateData(transform)
        }
    }

    private fun reset(
        store: DataStore<Preferences>,
        layoutStore: DataStore<Preferences> = store,
        foldersStore: DataStore<Preferences> = store,
        usage: AppUsageRepository = usageRepository,
    ) = ResetRepositoryImpl(
        homeLayoutRepository = HomeLayoutRepositoryImpl(layoutStore, HomeLayoutSerializer(), UuidItemIdFactory()),
        drawerFoldersRepository = DrawerFoldersRepositoryImpl(foldersStore, DrawerFoldersSerializer()),
        hiddenAppsRepository = HiddenAppsRepositoryImpl(store, HiddenAppsSerializer()),
        preferencesRepository = PreferencesRepositoryImpl(store),
        wallpaperDisplaySettings = NyxWallpaperDisplaySettings(store),
        fabPositionStore = NyxFabPositionStore(store),
        wallpaperRepository = WallpaperRepositoryImpl(store, fileManager, mainDispatcherRule.testDispatcher),
        appUsageRepository = usage,
        ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    @Test
    fun reset_clears_every_key_purges_usage_and_deletes_wallpaper_files() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = FakeDataStore()
            store.edit {
                it[stringPreferencesKey("home_layout_v1")] = "{...}"
                it[booleanPreferencesKey("monochrome_icons")] = true
                it[stringPreferencesKey("wallpaper_layers_json")] = "[...]"
                it[booleanPreferencesKey("home_dock_seeded_v1")] = true
            }
            usageRepository.recordPackageLaunch("com.a")

            val ok = reset(store).factoryReset()

            assertThat(ok).isTrue()
            assertThat(store.data.first().asMap()).isEmpty()
            // Usage lives in a separate store, so reset must purge it explicitly.
            assertThat(usageRepository.current).isEmpty()
            verify(exactly = 1) { fileManager.clearAll() }
        }

    @Test
    fun reset_is_incomplete_when_wallpaper_files_are_left() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { fileManager.clearAll() } returns false

            assertThat(reset(FakeDataStore()).factoryReset()).isFalse()
        }

    @Test
    fun a_failing_store_still_lets_the_others_run_and_is_reported() =
        runTest(mainDispatcherRule.testDispatcher) {
            // §Audit-2 N9: the stores are independent, so a failing one (usage) must NOT skip the
            // others — a reset clears as much as it can — while the result still reports it.
            val store = FakeDataStore()
            store.edit { it[stringPreferencesKey("home_layout_v1")] = "{...}" }
            val throwingUsage = mockk<AppUsageRepository> {
                coEvery { purgeRepository() } throws RuntimeException("usage store error")
            }

            val ok = reset(store, usage = throwingUsage).factoryReset()

            assertThat(ok).isFalse()
            assertThat(store.data.first().asMap()).isEmpty()
            verify(exactly = 1) { fileManager.clearAll() }
        }

    @Test
    fun a_failing_usage_store_is_reported_and_the_other_stores_are_purged() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The REAL usage purge over a store that refuses writes (F1: safePurge rethrows).
            val store = FakeDataStore()
            store.edit { it[stringPreferencesKey("home_layout_v1")] = "{...}" }
            val failingUsageStore = object : DataStore<Preferences> {
                override val data = flowOf(emptyPreferences())
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    throw IOException("usage store full")
            }

            val ok = reset(store, usage = AppUsageRepositoryImpl(failingUsageStore)).factoryReset()

            assertThat(ok).isFalse()
            assertThat(store.data.first().asMap()).isEmpty()
            verify(exactly = 1) { fileManager.clearAll() }
        }

    // ---- R2: seeding after an incomplete reset (2b-4c, step 3) ----

    @Test
    fun when_the_folder_purge_fails_the_dock_is_seeded_again_and_the_folders_stay() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = FakeDataStore()
            val foldersStore = FailOnceDataStore()
            val layout = HomeLayoutRepositoryImpl(store, HomeLayoutSerializer(), UuidItemIdFactory())
            val folders = DrawerFoldersRepositoryImpl(foldersStore, DrawerFoldersSerializer())
            layout.seedInitialLayout(resolveDockApps = { listOf(oldApp) }, resolveGridApps = { emptyList() })
            folders.seedInitialFolders { listOf(DrawerFolder(DrawerFolderId("old"), "Old", listOf(oldApp, other))) }
            val oldFolders = folders.folders().first()
            foldersStore.failNextEdit = true

            assertThat(reset(store, foldersStore = foldersStore).factoryReset()).isFalse()
            // What the settings do after every reset (FirstRunSeeder delegates to these two).
            layout.seedInitialLayout(resolveDockApps = { listOf(newApp) }, resolveGridApps = { emptyList() })
            val seededFolders = folders.seedInitialFolders {
                listOf(DrawerFolder(DrawerFolderId("new"), "New", listOf(newApp, other)))
            }

            assertThat(layout.layout().first().dock.map { (it as HomeItem.App).key }).containsExactly(newApp)
            assertThat(seededFolders).isFalse()
            assertThat(folders.folders().first()).isEqualTo(oldFolders)
        }

    @Test
    fun when_the_layout_purge_fails_the_old_layout_stays_and_seeding_leaves_it_alone() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = FakeDataStore()
            val layoutStore = FailOnceDataStore()
            val layout = HomeLayoutRepositoryImpl(layoutStore, HomeLayoutSerializer(), UuidItemIdFactory())
            layout.seedInitialLayout(resolveDockApps = { listOf(oldApp) }, resolveGridApps = { emptyList() })
            val oldLayout = layout.layout().first()
            layoutStore.failNextEdit = true

            assertThat(reset(store, layoutStore = layoutStore).factoryReset()).isFalse()
            val seeded = layout.seedInitialLayout(resolveDockApps = { listOf(newApp) }, resolveGridApps = { emptyList() })

            assertThat(seeded).isFalse()
            assertThat(layout.layout().first()).isEqualTo(oldLayout)
        }
}
