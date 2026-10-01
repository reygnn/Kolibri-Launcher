package com.github.reygnn.nyx_launcher.data.home

import android.content.Context
import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.feature.backup.contract.BackupRoundTripContract
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeDrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.FakePreferencesRepository
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Nyx's run of the shared [BackupRoundTripContract] (2b-2c) through [NyxBackupManager] and
 * the real engine. Stores are the domain fakes plus small in-test fakes for the wallpaper,
 * the display settings and the FAB; each write is logged per part. Wallpaper images are real
 * files (Robolectric for `Uri`), so the image bytes survive the comparison while the import
 * rebinds every layer to a new file.
 */
@RunWith(RobolectricTestRunner::class)
class NyxBackupRoundTripTest : BackupRoundTripContract<ImportOptions>() {

    @get:Rule
    val tmp = TemporaryFolder()

    private val log = ArrayList<String>()
    private lateinit var stores: Stores

    override val allOptions = ImportOptions()
    override val singleOptions = mapOf(
        NONE.copy(importLayout = true) to setOf(LAYOUT, FOLDERS),
        NONE.copy(importHiddenApps = true) to setOf(HIDDEN),
        NONE.copy(importSettings = true) to setOf(SETTINGS),
        NONE.copy(importWallpaper = true) to setOf(WALLPAPER),
    )
    override val replacingParts = setOf(LAYOUT, FOLDERS, HIDDEN, SETTINGS, WALLPAPER)
    override val writeLog: List<String> get() = log.toList()
    override val mostValuableParts = setOf(LAYOUT)
    override val wallpaperPart = WALLPAPER

    override suspend fun seed(variant: Variant) {
        stores = when (variant) {
            Variant.A -> Stores(
                layout = layoutOf(key("com.a1"), dock = key("com.a2")),
                folders = foldersOf("fa", key("com.a3"), key("com.a4")),
                hidden = setOf(key("com.ah")),
                prefs = FakePreferencesRepository(
                    iconStyle = IconStyle.GRAYSCALE, showAlarm = true, showCalendarEvent = true,
                    searchAutoLaunch = true, usageSort = true, notificationDots = true,
                ),
                scrim = 0.25f, backdrop = WallpaperBackdrop.BLACK, surface = WallpaperSurfaceMode.DARK,
                fab = FabPosition(0.2f, 0.3f),
                wallpaper = wallpaperOf(layer("la0", image(1), scale = 1.5f), layer("la1", image(2), translateX = 7f)),
            )
            Variant.B -> Stores(
                layout = layoutOf(key("com.b1"), dock = key("com.b2")),
                folders = foldersOf("fb", key("com.b3"), key("com.b4")),
                hidden = setOf(key("com.bh")),
                prefs = FakePreferencesRepository(iconStyle = IconStyle.MONOCHROME, showAlarm = true),
                scrim = 0.4f, backdrop = WallpaperBackdrop.SYSTEM_WALLPAPER, surface = WallpaperSurfaceMode.LIGHT,
                fab = FabPosition(0.6f, 0.1f),
                wallpaper = wallpaperOf(layer("lb0", image(3), translateY = -4f)),
            )
        }
    }

    override suspend fun freshStores() {
        stores = Stores()
    }

    override suspend fun snapshot(): Map<String, Any?> = mapOf(
        LAYOUT to stores.layout.current,
        FOLDERS to stores.folders.current,
        HIDDEN to stores.hidden.current,
        SETTINGS to listOf(
            stores.prefs.iconStyle().first(),
            stores.prefs.searchAutoLaunch().first(),
            stores.prefs.usageSortEnabled().first(),
            stores.prefs.notificationDots().first(),
            stores.prefs.showAlarmFlow.first(),
            stores.prefs.showCalendarEventFlow.first(),
            stores.display.scrim.value,
            stores.display.backdrop.value,
            stores.display.surface.value,
            stores.fab.value,
        ),
        // Id, transform and image bytes — never the URI, which the import rebinds.
        WALLPAPER to stores.wallpaper.state.value.layers.map { layer ->
            listOf(
                layer.id, layer.scale, layer.translateX, layer.translateY, layer.captureSampleSize,
                layer.imageUri?.let { File(Uri.parse(it).path!!).readBytes().toList() },
            )
        },
    )

    override suspend fun export(withWallpaper: Boolean): ByteArray {
        if (!withWallpaper) stores.wallpaper.state.value = WallpaperState.NONE
        val out = ByteArrayOutputStream()
        manager().writeBackup(out, timestamp = 1L)
        return out.toByteArray()
    }

    override suspend fun import(bytes: ByteArray, options: ImportOptions): Boolean {
        log.clear()
        return manager().importFrom({ ByteArrayInputStream(bytes) }, options) is ImportResult.Success
    }

    // ---- harness ----

    private fun manager() = NyxBackupManager(
        context = mockk<Context>(), // the stream-level writeBackup/importFrom never touch it
        homeLayoutRepository = stores.recordingLayout,
        drawerFoldersRepository = stores.recordingFolders,
        hiddenAppsRepository = stores.recordingHidden,
        preferences = stores.recordingPrefs,
        displaySettings = stores.display,
        wallpaperRepository = stores.wallpaper,
        fabPositionStore = stores.fabStore,
        fileManager = fileManager,
        serializer = NyxBackupSerializer(),
        reconcileHomeLayout = mockk<ReconcileHomeLayoutUseCase>(relaxed = true),
        engine = BackupEngine(mainDispatcherRule.testDispatcher, emptySet()),
        appVersionName = "test",
        ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    /** Copies an imported image into a real file, like the production file manager. */
    private val fileManager: WallpaperFileManager by lazy {
        mockk<WallpaperFileManager>(relaxed = true) {
            every { copyFromInputStream(any()) } answers {
                val file = File(tmp.root, "internal-${System.nanoTime()}.img")
                file.writeBytes(firstArg<InputStream>().readBytes())
                Uri.fromFile(file)
            }
        }
    }

    private inner class Stores(
        layout: HomeLayout = EMPTY_LAYOUT,
        folders: DrawerFolders = DrawerFolders.EMPTY,
        hidden: Set<ComponentKey> = emptySet(),
        val prefs: FakePreferencesRepository = FakePreferencesRepository(),
        scrim: Float = 0f,
        backdrop: WallpaperBackdrop = WallpaperBackdrop.SYSTEM_WALLPAPER,
        surface: WallpaperSurfaceMode = WallpaperSurfaceMode.AUTO,
        fab: FabPosition = FabPosition.DEFAULT,
        wallpaper: WallpaperState = WallpaperState.NONE,
    ) {
        val layout = FakeHomeLayoutRepository(layout)
        val folders = FakeDrawerFoldersRepository(folders)
        val hidden = FakeHiddenAppsRepository(hidden)
        val display = RecordingDisplaySettings(scrim, backdrop, surface)
        val wallpaper = RecordingWallpaperRepository(wallpaper)
        val fab = MutableStateFlow(fab)
        val fabStore = mockk<NyxFabPositionStore> {
            every { fabPositionFlow } returns this@Stores.fab
            coEvery { saveFabPosition(any()) } answers { log += SETTINGS; this@Stores.fab.value = firstArg() }
        }

        val recordingLayout = object : HomeLayoutRepository by this.layout {
            override suspend fun save(layout: HomeLayout) {
                log += LAYOUT
                this@Stores.layout.save(layout)
            }
        }
        val recordingFolders = object : DrawerFoldersRepository by this.folders {
            override suspend fun update(transform: suspend (DrawerFolders) -> DrawerFolders?) {
                log += FOLDERS
                this@Stores.folders.update(transform)
            }
        }
        val recordingHidden = object : HiddenAppsRepository by this.hidden {
            override suspend fun update(transform: suspend (Set<ComponentKey>) -> Set<ComponentKey>?) {
                log += HIDDEN
                this@Stores.hidden.update(transform)
            }
        }
        val recordingPrefs = object : PreferencesRepository by prefs {
            override suspend fun setIconStyle(style: IconStyle) { log += SETTINGS; prefs.setIconStyle(style) }
            override suspend fun setSearchAutoLaunch(enabled: Boolean) { log += SETTINGS; prefs.setSearchAutoLaunch(enabled) }
            override suspend fun setUsageSortEnabled(enabled: Boolean) { log += SETTINGS; prefs.setUsageSortEnabled(enabled) }
            override suspend fun setNotificationDots(enabled: Boolean) { log += SETTINGS; prefs.setNotificationDots(enabled) }
            override suspend fun setShowAlarm(enabled: Boolean) { log += SETTINGS; prefs.setShowAlarm(enabled) }
            override suspend fun setShowCalendarEvent(enabled: Boolean) { log += SETTINGS; prefs.setShowCalendarEvent(enabled) }
        }
    }

    private inner class RecordingDisplaySettings(
        scrim: Float,
        backdrop: WallpaperBackdrop,
        surface: WallpaperSurfaceMode,
    ) : WallpaperDisplaySettings {
        val scrim = MutableStateFlow(scrim)
        val backdrop = MutableStateFlow(backdrop)
        val surface = MutableStateFlow(surface)
        override val wallpaperScrimAlphaStateFlow: Flow<Float> = this.scrim
        override suspend fun setWallpaperScrimAlpha(alpha: Float) { log += SETTINGS; scrim.value = alpha }
        override val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode> = this.surface
        override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) { log += SETTINGS; surface.value = mode }
        override val wallpaperBackdropFlow: Flow<WallpaperBackdrop> = this.backdrop
        override suspend fun setWallpaperBackdrop(backdrop: WallpaperBackdrop) { log += SETTINGS; this.backdrop.value = backdrop }
    }

    private inner class RecordingWallpaperRepository(initial: WallpaperState) : WallpaperRepository {
        val state = MutableStateFlow(initial)
        override val wallpaperState: Flow<WallpaperState> = state
        override suspend fun saveWallpaperState(state: WallpaperState) { log += WALLPAPER; this.state.value = state }
        override suspend fun clearWallpaper() { log += WALLPAPER; state.value = WallpaperState.NONE }
        override suspend fun getWallpaperStateSync(): WallpaperState = state.value
        override suspend fun purgeRepository() { state.value = WallpaperState.NONE }
    }

    private fun key(pkg: String) = ComponentKey.of(pkg, "$pkg.Main")

    private fun layoutOf(item: ComponentKey, dock: ComponentKey) = HomeLayout(
        grid = GridSpec(columns = 4, rows = 6),
        pages = 1,
        items = listOf(PlacedItem(HomeItem.App(ItemId("i-${item.packageName}"), item), CellPos(0, 1, 2))),
        dock = listOf(HomeItem.App(ItemId("d-${dock.packageName}"), dock)),
    )

    private fun foldersOf(id: String, first: ComponentKey, second: ComponentKey) =
        DrawerFolders(listOf(DrawerFolder(DrawerFolderId(id), "Folder $id", listOf(first, second))))

    private fun image(seed: Int) = ByteArray(256) { (it * 13 + seed).toByte() }

    private fun layer(id: String, bytes: ByteArray, scale: Float = 1f, translateX: Float = 0f, translateY: Float = 0f): WallpaperLayerState {
        val file = File(tmp.root, "seed-$id-${System.nanoTime()}.img").apply { writeBytes(bytes) }
        return WallpaperLayerState(id = id, imageUri = Uri.fromFile(file).toString(), scale = scale, translateX = translateX, translateY = translateY)
    }

    private fun wallpaperOf(vararg layers: WallpaperLayerState) = WallpaperState.multiLayer(layers.toList())

    private companion object {
        const val LAYOUT = "layout"
        const val FOLDERS = "drawerFolders"
        const val HIDDEN = "hiddenApps"
        const val SETTINGS = "settings"
        const val WALLPAPER = "wallpaper"

        val NONE = ImportOptions(importLayout = false, importHiddenApps = false, importSettings = false, importWallpaper = false)

        val EMPTY_LAYOUT = HomeLayout(grid = GridSpec(columns = 4, rows = 6), pages = 1, items = emptyList(), dock = emptyList())
    }
}
