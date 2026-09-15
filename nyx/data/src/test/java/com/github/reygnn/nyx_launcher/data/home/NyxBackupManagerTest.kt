package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.nyx_launcher.home.model.ImportResult
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
import io.mockk.verify
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

    // ---- restoreWallpaper branches (import path; pure JVM — Uri is mocked, not parsed) ----

    private fun layerBackup(fileName: String) = WallpaperLayerBackup(
        id = "L-$fileName", imageFileName = fileName, scale = 1.5f, translateX = 2f, translateY = 3f,
    )

    /** A hand-built backup ZIP (manifest + optional blob entries), bypassing export(). */
    private fun zipOf(backup: NyxBackup, blobs: List<String> = emptyList()): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            zip.write(NyxBackupSerializer().serialize(backup).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            blobs.forEach { name ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun import_restores_blob_backed_wallpaper_layers() = runTest(mainDispatcherRule.dispatcher) {
        // Uri is mocked (not parsed) — restoreWallpaper only stores its toString().
        val uri = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns uri
        val saved = slot<WallpaperState>()
        coEvery { wallpaperRepository.saveWallpaperState(capture(saved)) } returns Unit

        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))
        manager.import(ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img"))), NyxBackupOptions())

        assertThat(saved.captured.layers).hasSize(1)
        assertThat(saved.captured.layers.single().imageUri).isEqualTo(uri.toString()) // extracted blob URI
        assertThat(saved.captured.layers.single().scale).isEqualTo(1.5f) // per-layer transform survives
    }

    @Test
    fun import_drops_layers_whose_blob_failed_to_extract() = runTest(mainDispatcherRule.dispatcher) {
        // Two layers; the first blob extracts, the second copy returns null → dropped.
        val ok = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns ok andThen null
        val saved = slot<WallpaperState>()
        coEvery { wallpaperRepository.saveWallpaperState(capture(saved)) } returns Unit

        val backup = NyxBackup(
            wallpaperLayers = listOf(
                layerBackup("wallpapers/layer_0.img"),
                layerBackup("wallpapers/layer_1.img"),
            ),
        )
        manager.import(
            ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img", "wallpapers/layer_1.img"))),
            NyxBackupOptions(),
        )

        assertThat(saved.captured.layers).hasSize(1) // the failed layer is dropped
        assertThat(saved.captured.layers.single().imageUri).isEqualTo(ok.toString()) // the surviving one
    }

    @Test
    fun import_keeps_current_wallpaper_when_every_blob_fails() = runTest(mainDispatcherRule.dispatcher) {
        every { fileManager.copyFromInputStream(any()) } returns null // corrupt backup: nothing extracts
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))
        manager.import(ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img"))), NyxBackupOptions())

        // restored is empty → the current wallpaper is left untouched (no save at all).
        coVerify(exactly = 0) { wallpaperRepository.saveWallpaperState(any()) }
    }

    @Test
    fun import_with_empty_wallpaper_layers_clears_the_current_wallpaper() = runTest(mainDispatcherRule.dispatcher) {
        manager.import(ByteArrayInputStream(zipOf(NyxBackup(wallpaperLayers = emptyList()))), NyxBackupOptions())
        coVerify { wallpaperRepository.saveWallpaperState(WallpaperState.NONE) }
    }

    @Test
    fun import_skips_wallpaper_when_option_off_even_with_layers() = runTest(mainDispatcherRule.dispatcher) {
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))
        manager.import(
            ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img"))),
            NyxBackupOptions(importWallpaper = false),
        )
        coVerify(exactly = 0) { wallpaperRepository.saveWallpaperState(any()) }
        verify(exactly = 0) { fileManager.copyFromInputStream(any()) } // blobs not even extracted
    }

    @Test
    fun import_skips_unknown_enum_pref_names_without_failing() = runTest(mainDispatcherRule.dispatcher) {
        val backup = NyxBackup(
            prefs = NyxBackupPrefs(
                monochromeIcons = true,
                backdrop = "NOT_A_REAL_BACKDROP", // invalid enum name → skipped, must not crash import
                surfaceMode = "ALSO_BOGUS",
            ),
        )
        val result = manager.import(ByteArrayInputStream(zipOf(backup)), NyxBackupOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        coVerify { preferences.setMonochromeIcons(true) } // valid pref still applied
        coVerify(exactly = 0) { displaySettings.setWallpaperBackdrop(any()) } // invalid enum skipped
        coVerify(exactly = 0) { displaySettings.setWallpaperSurfaceMode(any()) }
    }

    @Test
    fun import_leaves_the_home_layout_untouched_when_a_settings_write_fails() =
        runTest(mainDispatcherRule.dispatcher) {
            // Settings are applied before the layout write; a mid-import settings failure
            // must not have already replaced the existing home layout (layout is last).
            coEvery { preferences.setMonochromeIcons(any()) } throws java.io.IOException("disk full")
            val backup = NyxBackup(layout = layout.toDto(), prefs = NyxBackupPrefs(monochromeIcons = true))

            val result = manager.import(ByteArrayInputStream(zipOf(backup)), NyxBackupOptions())

            assertThat(result).isEqualTo(ImportResult.InvalidData)
            coVerify(exactly = 0) { homeLayoutRepository.save(any()) } // layout write never reached
        }
}
