package com.github.reygnn.nyx_launcher.data.home

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.ResetCompletenessContract
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Nyx's run of the shared [ResetCompletenessContract] (2b-4c). It first ran green against the
 * former `NyxResetManager` (one `clear()` of `home_layout`, the usage purge, `clearAll()`); since
 * step 3 it runs against [ResetRepositoryImpl] — per-store `Purgeable` purges — with the SAME
 * [inventory], which proves the new reset deletes exactly as much as the old one. Real
 * file-backed DataStores, the real repositories and the real wallpaper directory (Robolectric). Nyx keeps nothing across a reset — the seed flags go too and are
 * re-seeded afterwards (R2) — so [purgeExempt] stays empty and the check is "empty".
 *
 * [inventory] is the 2b-4c inventory of the former `NyxResetManager`: every key the reset has to remove,
 * including the legacy `monochrome_icons` an upgraded user still has.
 */
@RunWith(RobolectricTestRunner::class)
class NyxResetCompletenessTest : ResetCompletenessContract() {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wallpaperDir get() = File(context.filesDir, "wallpapers")

    private lateinit var homeLayoutStore: DataStore<Preferences>
    private lateinit var usageStore: DataStore<Preferences>
    private lateinit var reset: ResetRepositoryImpl

    /** Runs the DataStores; cancelled after each test so no store outlives it. */
    private var storeScope: CoroutineScope? = null

    @After
    fun closeStores() {
        storeScope?.cancel()
    }

    override val inventory = mapOf(
        HOME_LAYOUT to setOf(
            "home_layout_v1", "home_dock_seeded_v1",
            "drawer_folders_v1", "drawer_folders_seeded_v1",
            "hidden_apps_v1",
            "icon_style", "monochrome_icons", "search_auto_launch", "drawer_usage_sort",
            "notification_dots", "show_alarm", "show_calendar_event",
            "wallpaper_scrim_alpha", "wallpaper_backdrop", "wallpaper_surface_mode",
            "wallpaper_edit_fab_x_fraction", "wallpaper_edit_fab_y_fraction",
            "wallpaper_layers_json",
        ),
        USAGE to setOf("usage_com.used"),
    )

    override suspend fun seedEverything() {
        wallpaperDir.deleteRecursively()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()).also { storeScope = it }
        homeLayoutStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "home_layout.preferences_pb") }
        usageStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "nyx_usage.preferences_pb") }
        val fileManager = WallpaperFileManager(context)
        val appUsage = AppUsageRepositoryImpl(usageStore)

        val app = ComponentKey.of("com.seed", "com.seed.Main")
        val other = ComponentKey.of("com.other", "com.other.Main")
        val homeLayout = HomeLayoutRepositoryImpl(homeLayoutStore, HomeLayoutSerializer(), UuidItemIdFactory())
        homeLayout.seedInitialLayout(resolveDockApps = { listOf(app) }, resolveGridApps = { listOf(other) })
        val drawerFolders = DrawerFoldersRepositoryImpl(homeLayoutStore, DrawerFoldersSerializer())
        drawerFolders.seedInitialFolders { listOf(DrawerFolder(DrawerFolderId("f1"), "Seed", listOf(app, other))) }
        val hidden = HiddenAppsRepositoryImpl(homeLayoutStore, HiddenAppsSerializer())
        hidden.update { setOf(other) }
        val preferences = PreferencesRepositoryImpl(homeLayoutStore)
        with(preferences) {
            setIconStyle(IconStyle.GRAYSCALE)
            setSearchAutoLaunch(true)
            setUsageSortEnabled(true)
            setNotificationDots(true)
            setShowAlarm(true)
            setShowCalendarEvent(true)
        }
        // An upgraded user still carries the boolean from before the tri-state icon style.
        homeLayoutStore.edit { it[booleanPreferencesKey("monochrome_icons")] = true }
        val displaySettings = NyxWallpaperDisplaySettings(homeLayoutStore)
        with(displaySettings) {
            setWallpaperScrimAlpha(0.3f)
            setWallpaperBackdrop(WallpaperBackdrop.BLACK)
            setWallpaperSurfaceMode(WallpaperSurfaceMode.DARK)
        }
        val fab = NyxFabPositionStore(homeLayoutStore)
        fab.saveFabPosition(FabPosition(0.2f, 0.3f))
        val image = checkNotNull(fileManager.copyFromInputStream(ByteArrayInputStream(ByteArray(256) { it.toByte() })))
        val wallpaper = WallpaperRepositoryImpl(homeLayoutStore, fileManager, mainDispatcherRule.testDispatcher)
        wallpaper.saveWallpaperState(WallpaperState.single(image.toString()))
        appUsage.recordPackageLaunch("com.used")

        reset = ResetRepositoryImpl(
            homeLayoutRepository = homeLayout,
            drawerFoldersRepository = drawerFolders,
            hiddenAppsRepository = hidden,
            preferencesRepository = preferences,
            wallpaperDisplaySettings = displaySettings,
            fabPositionStore = fab,
            wallpaperRepository = wallpaper,
            appUsageRepository = appUsage,
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    override suspend fun factoryReset(): Boolean = reset.factoryReset()

    override suspend fun storedKeys(): Map<String, Set<String>> = mapOf(
        HOME_LAYOUT to homeLayoutStore.data.first().asMap().keys.mapTo(HashSet()) { it.name },
        USAGE to usageStore.data.first().asMap().keys.mapTo(HashSet()) { it.name },
    )

    override fun wallpaperFiles(): Set<String> = wallpaperDir.list().orEmpty().toSet()

    private companion object {
        const val HOME_LAYOUT = "home_layout"
        const val USAGE = "nyx_usage"
    }
}
