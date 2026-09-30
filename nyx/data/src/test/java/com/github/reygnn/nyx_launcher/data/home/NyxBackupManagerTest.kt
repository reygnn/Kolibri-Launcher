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
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.FakeDrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

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
        items = listOf(PlacedItem(HomeItem.App(ItemId("a1"), ComponentKey.of("com.x", "com.x.Main")), CellPos(0, 1, 2))),
        dock = listOf(HomeItem.App(ItemId("d1"), ComponentKey.of("com.y", "com.y.Main"))),
    )

    private val homeLayoutRepository = mockk<HomeLayoutRepository>(relaxed = true) {
        every { layout() } returns flowOf(this@NyxBackupManagerTest.layout)
    }
    private val preferences = mockk<PreferencesRepository>(relaxed = true) {
        every { iconStyle() } returns flowOf(IconStyle.MONOCHROME)
        every { searchAutoLaunch() } returns flowOf(false)
        every { usageSortEnabled() } returns flowOf(false)
        every { notificationDots() } returns flowOf(true)
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

    private val drawerFoldersRepository = FakeDrawerFoldersRepository()
    private val hiddenAppsRepository = FakeHiddenAppsRepository()

    // Named (not inline) so the layout-restore post-step can be verified: import saves the
    // layout THEN reconciles it, and reconcile is gated behind the importLayout toggle.
    private val reconcileHomeLayout = mockk<ReconcileHomeLayoutUseCase>(relaxed = true)

    private val manager = NyxBackupManager(
        homeLayoutRepository, drawerFoldersRepository, hiddenAppsRepository, preferences, displaySettings,
        wallpaperRepository, fabPositionStore, fileManager, NyxBackupSerializer(),
        reconcileHomeLayout, mainDispatcherRule.testDispatcher,
    )


    @Test
    fun export_then_import_restores_layout_and_prefs() = runTest(mainDispatcherRule.testDispatcher) {
        val out = ByteArrayOutputStream()
        assertThat(manager.export(out, appVersion = "0.1.2-dev", timestamp = 42L)).isTrue()

        // fresh manager for import with capturing mocks
        val savedLayout = slot<HomeLayout>()
        coEvery { homeLayoutRepository.save(capture(savedLayout)) } returns Unit

        val result = manager.import(ByteArrayInputStream(out.toByteArray()), NyxBackupOptions())

        assertThat(result).isInstanceOf(com.github.reygnn.nyx_launcher.home.model.ImportResult.Success::class.java)
        assertThat(savedLayout.captured.grid.columns).isEqualTo(4)
        assertThat(savedLayout.captured.items).hasSize(1)
        coVerify { preferences.setIconStyle(IconStyle.MONOCHROME) }
        coVerify { preferences.setSearchAutoLaunch(false) }
        coVerify { preferences.setNotificationDots(true) }
        coVerify { preferences.setShowAlarm(false) }
        coVerify { preferences.setShowCalendarEvent(true) }
        coVerify { displaySettings.setWallpaperScrimAlpha(0.3f) }
        coVerify { displaySettings.setWallpaperBackdrop(WallpaperBackdrop.BLACK) }
        coVerify { displaySettings.setWallpaperSurfaceMode(WallpaperSurfaceMode.DARK) }
        coVerify { fabPositionStore.saveFabPosition(FabPosition(0.8f, 0.7f)) }
    }

    @Test
    fun export_then_import_restores_drawer_folders() = runTest(mainDispatcherRule.testDispatcher) {
        drawerFoldersRepository.update {
            DrawerFolders(
                listOf(
                    DrawerFolder(
                        DrawerFolderId("f1"), "Work",
                        listOf(ComponentKey.of("com.a", "com.a.M"), ComponentKey.of("com.b", "com.b.M")),
                    ),
                ),
            )
        }
        val out = ByteArrayOutputStream()
        assertThat(manager.export(out, appVersion = "0.1.2-dev", timestamp = 7L)).isTrue()

        // Wipe, then import must bring the folder back (separate DataStore blob, D-5).
        drawerFoldersRepository.update { DrawerFolders.EMPTY }
        val result = manager.import(ByteArrayInputStream(out.toByteArray()), NyxBackupOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val folders = drawerFoldersRepository.current.folders
        assertThat(folders).hasSize(1)
        assertThat(folders.single().title).isEqualTo("Work")
        assertThat(folders.single().members)
            .containsExactly(ComponentKey.of("com.a", "com.a.M"), ComponentKey.of("com.b", "com.b.M")).inOrder()
    }

    @Test
    fun import_sanitizes_malformed_drawer_folders_before_persisting() = runTest(mainDispatcherRule.testDispatcher) {
        // §Audit-2 N10: a crafted / cross-device backup can carry a sub-two-member folder; the
        // restore must repair it (drop it here) rather than persist a malformed folder that only
        // heals at read time. Mirrors the home layout's post-restore reconcile.
        val malformed = DrawerFolders(
            listOf(DrawerFolder(DrawerFolderId("solo"), "Solo", listOf(ComponentKey.of("com.a", "com.a.M")))),
        )
        val result = manager.import(
            ByteArrayInputStream(zipOf(NyxBackup(drawerFolders = malformed.toDto()))),
            NyxBackupOptions(),
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(drawerFoldersRepository.current.folders).isEmpty() // the 1-member folder was dropped
    }

    @Test
    fun export_then_import_restores_hidden_apps() = runTest(mainDispatcherRule.testDispatcher) {
        hiddenAppsRepository.update { setOf(ComponentKey.of("com.a", "com.a.M"), ComponentKey.of("com.b", "com.b.M")) }
        val out = ByteArrayOutputStream()
        assertThat(manager.export(out, appVersion = "0.1.2-dev", timestamp = 7L)).isTrue()

        // Wipe, then import must bring the hidden set back (separate DataStore blob).
        hiddenAppsRepository.update { emptySet() }
        val result = manager.import(ByteArrayInputStream(out.toByteArray()), NyxBackupOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(hiddenAppsRepository.current)
            .containsExactly(ComponentKey.of("com.a", "com.a.M"), ComponentKey.of("com.b", "com.b.M"))
    }

    @Test
    fun import_with_null_hidden_apps_leaves_current_set_intact() = runTest(mainDispatcherRule.testDispatcher) {
        // An older backup carries no hiddenApps field (null); restore must not clear the current set.
        hiddenAppsRepository.update { setOf(ComponentKey.of("com.keep", "com.keep.M")) }
        val backup = NyxBackup(timestamp = 1L, appVersion = "old", hiddenApps = null)
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("backup.json"))
            it.write(NyxBackupSerializer().serialize(backup).toByteArray(Charsets.UTF_8))
            it.closeEntry()
        }

        val result = manager.import(ByteArrayInputStream(zip.toByteArray()), NyxBackupOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(hiddenAppsRepository.current).containsExactly(ComponentKey.of("com.keep", "com.keep.M"))
    }

    @Test
    fun malformed_zip_returns_invalid_data() = runTest(mainDispatcherRule.testDispatcher) {
        val result = manager.import(ByteArrayInputStream("not a zip".toByteArray()), NyxBackupOptions())
        assertThat(result).isEqualTo(com.github.reygnn.nyx_launcher.home.model.ImportResult.InvalidData)
    }

    @Test
    fun import_rejects_an_archive_with_too_many_wallpaper_blobs() = runTest(mainDispatcherRule.testDispatcher) {
        // Count cap (MAX_BLOB_ENTRIES = 64): a real backup has one blob per layer; an archive
        // spamming the wallpaper dir with far more entries is rejected before it can.
        every { fileManager.copyFromInputStream(any()) } returns null // extraction result irrelevant here
        val bytes = zipOf(NyxBackup(), blobs = (0..64).map { "wallpapers/layer_$it.img" }) // 65 > 64
        val result = manager.import(ByteArrayInputStream(bytes), NyxBackupOptions())
        assertThat(result).isEqualTo(ImportResult.InvalidData)
    }

    @Test
    fun import_rejects_a_blob_larger_than_the_per_blob_cap() = runTest(mainDispatcherRule.testDispatcher) {
        // Per-blob decompressed cap (MAX_BLOB_BYTES = 10 MiB): drain the stream like the real
        // copyFromInputStream so the per-blob CappedInputStream counts the bytes; an 11 MiB blob
        // (compresses tiny, so the whole-archive cap is untouched) trips the cap → InvalidData.
        every { fileManager.copyFromInputStream(any()) } answers {
            firstArg<java.io.InputStream>().readBytes(); null
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(NyxBackupSerializer().serialize(NyxBackup()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wallpapers/layer_0.img"))
            zip.write(ByteArray(11 * 1024 * 1024)) // 11 MiB decompressed > 10 MiB per-blob cap
            zip.closeEntry()
        }
        val result = manager.import(ByteArrayInputStream(bos.toByteArray()), NyxBackupOptions())
        assertThat(result).isEqualTo(ImportResult.InvalidData)
    }

    @Test
    fun import_rejects_an_archive_exceeding_the_whole_archive_cap() = runTest(mainDispatcherRule.testDispatcher) {
        // §Audit-2 N5: the whole-archive cap bounds the total COMPRESSED bytes ZipInputStream may
        // pull — including the closeEntry skip of a non-wallpaper padding entry. WITHOUT the cap
        // the padding is skipped and the (valid) manifest imports fine; WITH it the archive is
        // rejected before it can decompress unbounded. A single incompressible > 10 MiB entry that
        // is neither the manifest nor a wallpaper blob, so only the whole-archive cap can catch it.
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(NyxBackupSerializer().serialize(NyxBackup()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("pad.bin"))
            zip.write(Random(0).nextBytes(11 * 1024 * 1024)) // incompressible → ~11 MiB compressed > 10 MiB budget
            zip.closeEntry()
        }
        val result = manager.import(ByteArrayInputStream(bos.toByteArray()), NyxBackupOptions())
        assertThat(result).isEqualTo(ImportResult.InvalidData)
    }

    @Test
    fun options_gate_selective_import() = runTest(mainDispatcherRule.testDispatcher) {
        val out = ByteArrayOutputStream()
        manager.export(out, "v", 0L)

        manager.import(
            ByteArrayInputStream(out.toByteArray()),
            NyxBackupOptions(importLayout = false, importSettings = false, importWallpaper = false),
        )

        coVerify(exactly = 0) { homeLayoutRepository.save(any()) }
        coVerify(exactly = 0) { preferences.setIconStyle(any()) }
    }

    @Test
    fun import_saves_the_layout_then_reconciles_it() = runTest(mainDispatcherRule.testDispatcher) {
        // A restored layout must be reconciled AFTER it lands, not before: reconcile()
        // is a structural-only cleanup of what was just saved (dedup keys, 0-1 folders,
        // over-capacity dock). Order is the contract, so a cross-device backup renders
        // clean tiles this session, not on the next cold start only.
        val backup = NyxBackup(layout = layout.toDto())
        val result = manager.import(ByteArrayInputStream(zipOf(backup)), NyxBackupOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        coVerifyOrder {
            homeLayoutRepository.save(any())
            reconcileHomeLayout()
        }
    }

    @Test
    fun import_does_not_reconcile_when_layout_import_is_off() = runTest(mainDispatcherRule.testDispatcher) {
        // reconcile() is scoped to the layout restore; with importLayout=false the layout is
        // never saved, so there is nothing to reconcile — it must not run.
        val backup = NyxBackup(layout = layout.toDto())
        val result = manager.import(
            ByteArrayInputStream(zipOf(backup)),
            NyxBackupOptions(importLayout = false),
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        coVerify(exactly = 0) { homeLayoutRepository.save(any()) }
        coVerify(exactly = 0) { reconcileHomeLayout() }
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
    fun import_restores_blob_backed_wallpaper_layers() = runTest(mainDispatcherRule.testDispatcher) {
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
    fun import_drops_layers_whose_blob_failed_to_extract() = runTest(mainDispatcherRule.testDispatcher) {
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
    fun import_keeps_current_wallpaper_when_every_blob_fails() = runTest(mainDispatcherRule.testDispatcher) {
        every { fileManager.copyFromInputStream(any()) } returns null // corrupt backup: nothing extracts
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))
        manager.import(ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img"))), NyxBackupOptions())

        // restored is empty → the current wallpaper is left untouched (no save at all).
        coVerify(exactly = 0) { wallpaperRepository.saveWallpaperState(any()) }
    }

    // ---- §Audit-3 A3-05: extracted blobs never outlive a failed / partial import ----

    @Test
    fun import_deletes_extracted_blobs_when_the_manifest_is_invalid() = runTest(mainDispatcherRule.testDispatcher) {
        // Blobs are extracted BEFORE the manifest is parsed; a garbage manifest aborts the import
        // and must not leave the already-extracted blob orphaned in internal storage.
        val uri = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns uri
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write("{ not json".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wallpapers/layer_0.img"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val result = manager.import(ByteArrayInputStream(bos.toByteArray()), NyxBackupOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidData)
        val extractedUri = uri.toString()
        verify { fileManager.deleteFile(extractedUri) }
    }

    @Test
    fun import_deletes_a_blob_that_no_restored_layer_references() = runTest(mainDispatcherRule.testDispatcher) {
        // Two blobs extracted, but the manifest only references layer_0 → layer_1 is garbage.
        val used = mockk<Uri>()
        val stray = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns used andThen stray
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))

        manager.import(
            ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img", "wallpapers/layer_1.img"))),
            NyxBackupOptions(),
        )

        // Resolve the strings OUTSIDE verify{}: a mock's toString() inside the block is recorded as a call.
        val strayUri = stray.toString()
        val usedUri = used.toString()
        verify { fileManager.deleteFile(strayUri) }
        verify(exactly = 0) { fileManager.deleteFile(usedUri) } // the restored layer's file stays
    }

    @Test
    fun import_keeps_restored_blobs_when_a_later_step_fails() = runTest(mainDispatcherRule.testDispatcher) {
        // The wallpaper state is saved (and so references the blob) before the layout step; a
        // failure AFTER that save must not delete a file the persisted wallpaper now points at.
        val uri = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns uri
        coEvery { homeLayoutRepository.save(any()) } throws java.io.IOException("disk full")
        val backup = NyxBackup(layout = layout.toDto(), wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))

        val result = manager.import(ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img"))), NyxBackupOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidData)
        coVerify { wallpaperRepository.saveWallpaperState(any()) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun import_keeps_claimed_blobs_when_the_wallpaper_save_is_interrupted() = runTest(mainDispatcherRule.testDispatcher) {
        // The blobs are claimed BEFORE the save: DataStore can commit the write and the call still
        // end in a CancellationException (import runs in the settings screen's lifecycleScope).
        // Deleting then would break the persisted wallpaper, so an interrupted save keeps them
        // (at worst an orphan for the startup sweep).
        val uri = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns uri
        coEvery { wallpaperRepository.saveWallpaperState(any()) } throws kotlinx.coroutines.CancellationException("left settings")
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))

        runCatching {
            manager.import(ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img"))), NyxBackupOptions())
        }

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun import_with_empty_wallpaper_layers_clears_the_current_wallpaper() = runTest(mainDispatcherRule.testDispatcher) {
        manager.import(ByteArrayInputStream(zipOf(NyxBackup(wallpaperLayers = emptyList()))), NyxBackupOptions())
        coVerify { wallpaperRepository.saveWallpaperState(WallpaperState.NONE) }
    }

    @Test
    fun import_skips_wallpaper_when_option_off_even_with_layers() = runTest(mainDispatcherRule.testDispatcher) {
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup("wallpapers/layer_0.img")))
        manager.import(
            ByteArrayInputStream(zipOf(backup, listOf("wallpapers/layer_0.img"))),
            NyxBackupOptions(importWallpaper = false),
        )
        coVerify(exactly = 0) { wallpaperRepository.saveWallpaperState(any()) }
        verify(exactly = 0) { fileManager.copyFromInputStream(any()) } // blobs not even extracted
    }

    @Test
    fun import_skips_unknown_enum_pref_names_without_failing() = runTest(mainDispatcherRule.testDispatcher) {
        val backup = NyxBackup(
            prefs = NyxBackupPrefs(
                monochromeIcons = true,
                backdrop = "NOT_A_REAL_BACKDROP", // invalid enum name → skipped, must not crash import
                surfaceMode = "ALSO_BOGUS",
            ),
        )
        val result = manager.import(ByteArrayInputStream(zipOf(backup)), NyxBackupOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        // Legacy-only backup (monochromeIcons=true) still maps to the tri-state setter.
        coVerify { preferences.setIconStyle(IconStyle.MONOCHROME) } // valid pref still applied
        coVerify(exactly = 0) { displaySettings.setWallpaperBackdrop(any()) } // invalid enum skipped
        coVerify(exactly = 0) { displaySettings.setWallpaperSurfaceMode(any()) }
    }

    @Test
    fun import_leaves_the_home_layout_untouched_when_a_settings_write_fails() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Settings are applied before the layout write; a mid-import settings failure
            // must not have already replaced the existing home layout (layout is last).
            coEvery { preferences.setIconStyle(any()) } throws java.io.IOException("disk full")
            val backup = NyxBackup(layout = layout.toDto(), prefs = NyxBackupPrefs(monochromeIcons = true))

            val result = manager.import(ByteArrayInputStream(zipOf(backup)), NyxBackupOptions())

            assertThat(result).isEqualTo(ImportResult.InvalidData)
            coVerify(exactly = 0) { homeLayoutRepository.save(any()) } // layout write never reached
        }
}
