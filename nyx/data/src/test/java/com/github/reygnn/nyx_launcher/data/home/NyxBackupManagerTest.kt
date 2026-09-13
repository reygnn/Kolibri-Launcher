package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.FabPosition
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Round-trips a backup through the ZIP container (export → import) with mocked repos,
 * pinning the assembler both directions. Wallpaper blobs (file I/O) are covered on
 * device; here wallpaper is empty so no Uri parsing is needed (pure JVM).
 */
class NyxBackupManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val layout = HomeLayout(
        grid = GridSpec(columns = 4, rows = 6),
        pages = 2,
        items = listOf(PlacedItem(HomeItem.App(ItemId("a1"), ComponentKey("com.x", "com.x.Main")), CellPos(0, 1, 2))),
        dock = listOf(HomeItem.App(ItemId("d1"), ComponentKey("com.y", "com.y.Main"))),
    )

    private val homeLayoutRepository = mockk<HomeLayoutRepository>(relaxed = true) {
        every { layout() } returns flowOf(this@NyxBackupManagerTest.layout)
    }
    private val preferences = mockk<PreferencesRepository>(relaxed = true) {
        every { monochromeIcons() } returns flowOf(true)
        every { showAlarmFlow } returns flowOf(false)
        every { showCalendarEventFlow } returns flowOf(true)
    }
    private val displaySettings = mockk<WallpaperDisplaySettings>(relaxed = true) {
        every { wallpaperScrimAlphaStateFlow } returns flowOf(0.3f)
        every { wallpaperBackdropFlow } returns flowOf(WallpaperBackdrop.BLACK)
        every { wallpaperSurfaceModeFlow } returns flowOf(WallpaperSurfaceMode.DARK)
    }
    private val wallpaperRepository = mockk<WallpaperRepository>(relaxed = true) {
        coEvery { getWallpaperStateSync() } returns WallpaperState.NONE
    }
    private val fabPositionStore = mockk<NyxFabPositionStore>(relaxed = true) {
        every { fabPositionFlow } returns flowOf(FabPosition(0.8f, 0.7f))
    }
    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)

    private val manager = NyxBackupManager(
        homeLayoutRepository, preferences, displaySettings, wallpaperRepository,
        fabPositionStore, fileManager, NyxBackupSerializer(), mainDispatcherRule.dispatcher,
    )


    @Test
    fun export_then_import_restores_layout_and_prefs() = runTest(mainDispatcherRule.dispatcher) {
        val out = ByteArrayOutputStream()
        assertThat(manager.export(out, appVersion = "0.1.2-dev", timestamp = 42L)).isTrue()

        // fresh manager for import with capturing mocks
        val savedLayout = slot<HomeLayout>()
        coEvery { homeLayoutRepository.save(capture(savedLayout)) } returns Unit

        val result = manager.import(ByteArrayInputStream(out.toByteArray()), NyxBackupOptions())

        assertThat(result).isInstanceOf(com.github.reygnn.nyx_launcher.home.model.ImportResult.Success::class.java)
        assertThat(savedLayout.captured.grid.columns).isEqualTo(4)
        assertThat(savedLayout.captured.items).hasSize(1)
        coVerify { preferences.setMonochromeIcons(true) }
        coVerify { preferences.setShowAlarm(false) }
        coVerify { preferences.setShowCalendarEvent(true) }
        coVerify { displaySettings.setWallpaperScrimAlpha(0.3f) }
        coVerify { displaySettings.setWallpaperBackdrop(WallpaperBackdrop.BLACK) }
        coVerify { displaySettings.setWallpaperSurfaceMode(WallpaperSurfaceMode.DARK) }
        coVerify { fabPositionStore.saveFabPosition(FabPosition(0.8f, 0.7f)) }
    }

    @Test
    fun malformed_zip_returns_invalid_data() = runTest(mainDispatcherRule.dispatcher) {
        val result = manager.import(ByteArrayInputStream("not a zip".toByteArray()), NyxBackupOptions())
        assertThat(result).isEqualTo(com.github.reygnn.nyx_launcher.home.model.ImportResult.InvalidData)
    }

    @Test
    fun options_gate_selective_import() = runTest(mainDispatcherRule.dispatcher) {
        val out = ByteArrayOutputStream()
        manager.export(out, "v", 0L)

        manager.import(
            ByteArrayInputStream(out.toByteArray()),
            NyxBackupOptions(importLayout = false, importSettings = false, importWallpaper = false),
        )

        coVerify(exactly = 0) { homeLayoutRepository.save(any()) }
        coVerify(exactly = 0) { preferences.setMonochromeIcons(any()) }
    }
}
