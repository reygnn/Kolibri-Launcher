package com.github.reygnn.nyx_launcher.data.home

import android.content.Context
import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
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
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.container.ContainerManifestCodec
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.launcher.feature.backup.engine.BackupRead
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Round-trips a backup through the shared E5a container (export → import, 2b-1) with mocked
 * repos, pinning the assembler both directions. Containers for the import-only cases are
 * written by the real engine ([containerOf]) or, for hostile archives, by hand. Uri is
 * mocked, not parsed (pure JVM).
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

    private val engine = BackupEngine(mainDispatcherRule.testDispatcher, emptySet())
    private val serializer = NyxBackupSerializer()

    // The stream-level writeBackup/importFrom never touch the Context; the SAF path is
    // NyxBackupSavePathTest's.
    private val manager = NyxBackupManager(
        mockk<Context>(), homeLayoutRepository, drawerFoldersRepository, hiddenAppsRepository, preferences, displaySettings,
        wallpaperRepository, fabPositionStore, fileManager, serializer,
        reconcileHomeLayout, engine, appVersionName = "0.2.0", ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    @Test
    fun import_sanitizes_malformed_drawer_folders_before_persisting() = runTest(mainDispatcherRule.testDispatcher) {
        // §Audit-2 N10: a crafted / cross-device backup can carry a sub-two-member folder; the
        // restore must repair it (drop it here) rather than persist a malformed folder that only
        // heals at read time. Mirrors the home layout's post-restore reconcile.
        val malformed = DrawerFolders(
            listOf(DrawerFolder(DrawerFolderId("solo"), "Solo", listOf(ComponentKey.of("com.a", "com.a.M")))),
        )
        val result = manager.importFrom(
            opener(containerOf(NyxBackup(drawerFolders = malformed.toDto()))),
            ImportOptions(),
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(drawerFoldersRepository.current.folders).isEmpty() // the 1-member folder was dropped
    }

    @Test
    fun import_with_null_hidden_apps_leaves_current_set_intact() = runTest(mainDispatcherRule.testDispatcher) {
        // A backup without a hiddenApps field (null); restore must not clear the current set.
        hiddenAppsRepository.update { setOf(ComponentKey.of("com.keep", "com.keep.M")) }

        val result = manager.importFrom(opener(containerOf(NyxBackup(hiddenApps = null))), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(hiddenAppsRepository.current).containsExactly(ComponentKey.of("com.keep", "com.keep.M"))
    }

    @Test
    fun hidden_apps_follow_their_own_switch() = runTest(mainDispatcherRule.testDispatcher) {
        // 2b-3: own switch, like Kolibri's. On: B13, the set is replaced. The layout switch no
        // longer carries them, and switching hidden apps on alone leaves the layout alone.
        val restored = ComponentKey.of("com.restored", "com.restored.M")
        hiddenAppsRepository.update { setOf(ComponentKey.of("com.keep", "com.keep.M")) }
        val bytes = containerOf(NyxBackup(layout = layout.toDto(), hiddenApps = listOf(restored.toDto())))

        manager.importFrom(opener(bytes), ImportOptions(importLayout = true, importHiddenApps = false, importSettings = false, importWallpaper = false))
        assertThat(hiddenAppsRepository.current).containsExactly(ComponentKey.of("com.keep", "com.keep.M"))

        manager.importFrom(opener(bytes), ImportOptions(importLayout = false, importHiddenApps = true, importSettings = false, importWallpaper = false))
        assertThat(hiddenAppsRepository.current).containsExactly(restored)
        coVerify(exactly = 1) { homeLayoutRepository.save(any()) } // only the first, layout-on import
    }

    @Test
    fun malformed_zip_returns_invalid_format() = runTest(mainDispatcherRule.testDispatcher) {
        val result = manager.importFrom(opener("not a zip".toByteArray()), ImportOptions())
        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun import_rejects_an_archive_with_too_many_wallpaper_blobs() = runTest(mainDispatcherRule.testDispatcher) {
        // Count cap (engine, 64 blobs): a real backup has one blob per distinct layer image; a
        // manifest listing far more is refused before anything is staged or copied.
        val table = (0..64).map { ContainerManifest.Blob(sha256Of(byteArrayOf(it.toByte())), 1, "image/*") } // 65 > 64
        val result = manager.importFrom(opener(handMade(table)), ImportOptions())
        assertThat(result).isEqualTo(ImportResult.Error("Backup file is too large"))
        verify(exactly = 0) { fileManager.copyFromInputStream(any()) }
    }

    @Test
    fun import_rejects_a_blob_declared_larger_than_the_per_blob_cap() = runTest(mainDispatcherRule.testDispatcher) {
        // Per-blob cap (engine, 10 MiB): the manifest's table declares every blob's size, so an
        // over-cap blob is refused from the table alone, before a byte of it is read.
        val table = listOf(ContainerManifest.Blob(sha256Of(byteArrayOf(1)), 11L * 1024 * 1024, "image/*"))
        val result = manager.importFrom(opener(handMade(table)), ImportOptions())
        assertThat(result).isEqualTo(ImportResult.Error("Backup file is too large"))
    }

    @Test
    fun a_blob_larger_than_its_table_entry_drops_only_its_layer() = runTest(mainDispatcherRule.testDispatcher) {
        // BackupFormatContract: a size or hash mismatch rejects just that blob — its layer is
        // dropped (here the only one, so the current wallpaper stays), the rest imports.
        val declared = byteArrayOf(1, 2, 3)
        val table = listOf(ContainerManifest.Blob(sha256Of(declared), declared.size.toLong(), "image/*"))
        val backup = NyxBackup(
            layout = layout.toDto(),
            wallpaperLayers = listOf(layerBackup(0).copy(imageFileName = table.single().sha256)),
        )
        val bytes = handMade(table, section = serializer.toJson(backup), blobEntries = mapOf(table.single().sha256 to ByteArray(11 * 1024 * 1024)))

        val result = manager.importFrom(opener(bytes), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.Success(droppedWallpaperLayers = 1))
        verify(exactly = 0) { fileManager.copyFromInputStream(any()) }
        coVerify(exactly = 0) { wallpaperRepository.saveWallpaperState(any()) }
        coVerify { homeLayoutRepository.save(any()) }
    }

    @Test
    fun import_rejects_an_archive_exceeding_the_whole_archive_cap() = runTest(mainDispatcherRule.testDispatcher) {
        // §Audit-2 N5: the whole-archive cap bounds the total COMPRESSED bytes the reader may
        // pull — including the skip of an entry the manifest does not list. WITHOUT the cap
        // the padding is skipped and the (valid) section imports fine; WITH it the archive is
        // rejected before it can decompress unbounded. A single incompressible > 10 MiB entry
        // that is no listed blob, so only the whole-archive cap can catch it.
        val bytes = handMade(
            table = emptyList(),
            section = serializer.toJson(NyxBackup(layout = layout.toDto())),
            extraEntries = mapOf("pad.bin" to Random(0).nextBytes(11 * 1024 * 1024)), // ~11 MiB compressed > 10 MiB budget
        )
        val result = manager.importFrom(opener(bytes), ImportOptions())
        assertThat(result).isEqualTo(ImportResult.Error("Backup file is too large"))
        coVerify(exactly = 0) { homeLayoutRepository.save(any()) }
    }

    @Test
    fun export_writes_one_nyx_section_under_the_nyx_producer() = runTest(mainDispatcherRule.testDispatcher) {
        val out = ByteArrayOutputStream()
        manager.writeBackup(out, timestamp = 99L)

        val manifest = engine.readStaged(opener(out.toByteArray()), NyxBackupSchema.APP_ID, NyxBackupSchema.KNOWN_SECTIONS, "test") { read, _ ->
            (read as BackupRead.Ok).manifest
        }

        assertThat(manifest.producer).isEqualTo(ContainerManifest.Producer("nyx", "0.2.0", 99L))
        assertThat(manifest.schemaVersion).isEqualTo(NyxBackupSchema.SCHEMA_VERSION)
        assertThat(manifest.sections.keys).containsExactly(NyxBackupSchema.SECTION_BACKUP)
    }

    @Test
    fun every_icon_style_survives_the_round_trip() = runTest(mainDispatcherRule.testDispatcher) {
        // The tri-state icon style is a core Nyx feature; the backup carries it as `iconStyle`.
        for (style in IconStyle.entries) {
            every { preferences.iconStyle() } returns flowOf(style)
            val out = ByteArrayOutputStream()
            manager.writeBackup(out)

            manager.importFrom(opener(out.toByteArray()), ImportOptions())

            coVerify { preferences.setIconStyle(style) }
        }
    }

    @Test
    fun options_gate_selective_import() = runTest(mainDispatcherRule.testDispatcher) {
        val out = ByteArrayOutputStream()
        manager.writeBackup(out)

        manager.importFrom(
            opener(out.toByteArray()),
            ImportOptions(importLayout = false, importHiddenApps = false, importSettings = false, importWallpaper = false),
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
        val result = manager.importFrom(opener(containerOf(backup)), ImportOptions())

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
        val result = manager.importFrom(
            opener(containerOf(backup)),
            ImportOptions(importLayout = false),
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        coVerify(exactly = 0) { homeLayoutRepository.save(any()) }
        coVerify(exactly = 0) { reconcileHomeLayout() }
    }

    // ---- restoreWallpaper branches (import path; pure JVM — Uri is mocked, not parsed) ----

    /** A layer referencing blob [blobIndex] of [containerOf]; the hash is filled in there. */
    private fun layerBackup(blobIndex: Int) = WallpaperLayerBackup(
        id = "L-$blobIndex", imageFileName = "$BLOB_REF$blobIndex", scale = 1.5f, translateX = 2f, translateY = 3f,
    )

    private fun image(seed: Int) = ByteArray(64) { (it * 31 + seed).toByte() }

    /**
     * A container written by the real engine, bypassing export(): [blobs] become blobs, and
     * every layer's `#<i>` reference becomes the hash of blobs[i].
     */
    private suspend fun containerOf(backup: NyxBackup, blobs: List<ByteArray> = emptyList(), appId: String = NyxBackupSchema.APP_ID): ByteArray {
        val out = ByteArrayOutputStream()
        engine.export(
            out,
            ContainerManifest.Producer(appId, "test", 1L),
            NyxBackupSchema.SCHEMA_VERSION,
            blobs.map { bytes -> BlobSource("image/*", opener(bytes)) },
        ) { hashes ->
            val layers = backup.wallpaperLayers.map { layer ->
                val index = layer.imageFileName?.removePrefix(BLOB_REF)?.toIntOrNull()
                if (index != null) layer.copy(imageFileName = hashes[index]) else layer
            }
            mapOf(NyxBackupSchema.SECTION_BACKUP to ContainerManifest.Section(NyxBackupSchema.SECTION_VERSION, serializer.toJson(backup.copy(wallpaperLayers = layers))))
        }
        return out.toByteArray()
    }

    /** A hand-written container for cases the engine would never write (hostile tables, padding). */
    private fun handMade(
        table: List<ContainerManifest.Blob>,
        section: JsonElement = serializer.toJson(NyxBackup()),
        blobEntries: Map<String, ByteArray> = emptyMap(),
        extraEntries: Map<String, ByteArray> = emptyMap(),
        manifestText: (String) -> String = { it },
    ): ByteArray {
        val manifest = ContainerManifest(
            producer = ContainerManifest.Producer(NyxBackupSchema.APP_ID, "test", 1L),
            schemaVersion = NyxBackupSchema.SCHEMA_VERSION,
            blobs = table,
            sections = mapOf(NyxBackupSchema.SECTION_BACKUP to ContainerManifest.Section(NyxBackupSchema.SECTION_VERSION, section)),
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestText(ContainerManifestCodec.encode(manifest).toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            (blobEntries.mapKeys { "blobs/${it.key}" } + extraEntries).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** A fresh stream per open, as the engine expects of an opener. */
    private fun opener(bytes: ByteArray): () -> InputStream = { ByteArrayInputStream(bytes) }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun import_restores_blob_backed_wallpaper_layers() = runTest(mainDispatcherRule.testDispatcher) {
        // Uri is mocked (not parsed) — restoreWallpaper only stores its toString().
        val uri = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns uri
        val saved = slot<WallpaperState>()
        coEvery { wallpaperRepository.saveWallpaperState(capture(saved)) } returns Unit

        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup(0)))
        manager.importFrom(opener(containerOf(backup, listOf(image(0)))), ImportOptions())

        assertThat(saved.captured.layers).hasSize(1)
        assertThat(saved.captured.layers.single().imageUri).isEqualTo(uri.toString()) // copied blob URI
        assertThat(saved.captured.layers.single().scale).isEqualTo(1.5f) // per-layer transform survives
    }

    @Test
    fun import_drops_layers_whose_blob_failed_to_extract() = runTest(mainDispatcherRule.testDispatcher) {
        // Two layers; the first blob extracts, the second copy returns null → dropped.
        val ok = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns ok andThen null
        val saved = slot<WallpaperState>()
        coEvery { wallpaperRepository.saveWallpaperState(capture(saved)) } returns Unit

        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup(0), layerBackup(1)))
        manager.importFrom(opener(containerOf(backup, listOf(image(0), image(1)))), ImportOptions())

        assertThat(saved.captured.layers).hasSize(1) // the failed layer is dropped
        assertThat(saved.captured.layers.single().imageUri).isEqualTo(ok.toString()) // the surviving one
    }

    @Test
    fun import_keeps_current_wallpaper_when_every_blob_fails() = runTest(mainDispatcherRule.testDispatcher) {
        every { fileManager.copyFromInputStream(any()) } returns null // nothing copies
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup(0)))
        manager.importFrom(opener(containerOf(backup, listOf(image(0)))), ImportOptions())

        // restored is empty → the current wallpaper is left untouched (no save at all).
        coVerify(exactly = 0) { wallpaperRepository.saveWallpaperState(any()) }
    }

    // ---- §Audit-3 A3-05: extracted blobs never outlive a failed / partial import ----

    @Test
    fun an_unreadable_section_copies_no_blob() = runTest(mainDispatcherRule.testDispatcher) {
        // Blobs are only staged by the engine; nothing reaches internal storage before the
        // section has decoded, so an unreadable section leaves no file behind to delete.
        val blob = byteArrayOf(1, 2, 3)
        val table = listOf(ContainerManifest.Blob(sha256Of(blob), blob.size.toLong(), "image/*"))
        val bytes = handMade(table, section = JsonPrimitive("{ not a backup"), blobEntries = mapOf(table.single().sha256 to blob))

        val result = manager.importFrom(opener(bytes), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
        verify(exactly = 0) { fileManager.copyFromInputStream(any()) }
    }

    @Test
    fun a_blob_no_layer_references_is_never_copied() = runTest(mainDispatcherRule.testDispatcher) {
        // Two blobs staged, but the section only references the first → the second is left to
        // the engine's cleanup and never lands in internal storage.
        val used = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns used
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup(0)))

        manager.importFrom(opener(containerOf(backup, listOf(image(0), image(1)))), ImportOptions())

        // Resolve the string OUTSIDE verify{}: a mock's toString() inside the block is recorded as a call.
        val usedUri = used.toString()
        verify(exactly = 1) { fileManager.copyFromInputStream(any()) }
        verify(exactly = 0) { fileManager.deleteFile(usedUri) } // the restored layer's file stays
    }

    @Test
    fun layers_sharing_one_image_get_one_file_each() = runTest(mainDispatcherRule.testDispatcher) {
        // The container stores an image once, but removing a layer deletes its file right away —
        // so each layer gets its own copy, or deleting one would break the other.
        val first = mockk<Uri>()
        val second = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns first andThen second
        val saved = slot<WallpaperState>()
        coEvery { wallpaperRepository.saveWallpaperState(capture(saved)) } returns Unit
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup(0), layerBackup(0).copy(id = "L-copy")))

        manager.importFrom(opener(containerOf(backup, listOf(image(0)))), ImportOptions())

        val firstUri = first.toString()
        val secondUri = second.toString()
        assertThat(saved.captured.layers.map { it.imageUri }).containsExactly(firstUri, secondUri).inOrder()
    }

    @Test
    fun import_keeps_restored_blobs_when_a_later_step_fails() = runTest(mainDispatcherRule.testDispatcher) {
        // The wallpaper state is saved (and so references the blob) before the layout step; a
        // failure AFTER that save must not delete a file the persisted wallpaper now points at.
        val uri = mockk<Uri>()
        every { fileManager.copyFromInputStream(any()) } returns uri
        coEvery { homeLayoutRepository.save(any()) } throws java.io.IOException("disk full")
        val backup = NyxBackup(layout = layout.toDto(), wallpaperLayers = listOf(layerBackup(0)))

        val result = manager.importFrom(opener(containerOf(backup, listOf(image(0)))), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
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
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup(0)))

        runCatching {
            manager.importFrom(opener(containerOf(backup, listOf(image(0)))), ImportOptions())
        }

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    // ---- B11 clamping and non-finite values (2b-2, 2b-2b). Round trip, E1, B14, U4 and E2
    // run in the shared contracts: NyxBackupRoundTripTest, NyxImportKeepsMissingAppsTest. ----

    @Test
    fun imported_values_outside_their_range_are_clamped() = runTest(mainDispatcherRule.testDispatcher) {
        // B11: the scrim keeps to the app's range, the FAB centre to the parent's [0, 1].
        val backup = NyxBackup(prefs = NyxBackupPrefs(scrimAlpha = 0.9f, fabXFraction = 1.7f, fabYFraction = -0.2f))

        manager.importFrom(opener(containerOf(backup)), ImportOptions())

        coVerify { displaySettings.setWallpaperScrimAlpha(AppConstants.WALLPAPER_SCRIM_ALPHA_MAX) }
        coVerify { fabPositionStore.saveFabPosition(FabPosition(1f, 0f)) }
    }

    @Test
    fun a_non_finite_scrim_makes_the_backup_invalid_and_writes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        // The app can never write NaN/Infinity (encode throws, NyxBackupSerializerTest). A crafted
        // backup carrying one is rejected whole at decode, so it never reaches a store; the
        // non-finite branch of coerceInSafe is defense in depth, not the live path.
        val placeholder = "0.123456"
        val backup = NyxBackup(layout = layout.toDto(), prefs = NyxBackupPrefs(scrimAlpha = placeholder.toFloat()))
        val bytes = handMade(emptyList(), section = serializer.toJson(backup)) { manifest ->
            check(manifest.contains(placeholder)) { "placeholder not in manifest" }
            manifest.replace(placeholder, "1e309") // valid JSON number, Infinity as a Float
        }

        val result = manager.importFrom(opener(bytes), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
        coVerify(exactly = 0) { displaySettings.setWallpaperScrimAlpha(any()) }
        coVerify(exactly = 0) { homeLayoutRepository.save(any()) }
    }

    @Test
    fun import_skips_wallpaper_when_option_off_even_with_layers() = runTest(mainDispatcherRule.testDispatcher) {
        val backup = NyxBackup(wallpaperLayers = listOf(layerBackup(0)))
        manager.importFrom(
            opener(containerOf(backup, listOf(image(0)))),
            ImportOptions(importWallpaper = false),
        )
        coVerify(exactly = 0) { wallpaperRepository.saveWallpaperState(any()) }
        verify(exactly = 0) { fileManager.copyFromInputStream(any()) } // blobs not even copied
    }

    @Test
    fun import_skips_unknown_enum_pref_names_without_failing() = runTest(mainDispatcherRule.testDispatcher) {
        val backup = NyxBackup(
            prefs = NyxBackupPrefs(
                iconStyle = "MONOCHROME",
                backdrop = "NOT_A_REAL_BACKDROP", // invalid enum name → skipped, must not crash import
                surfaceMode = "ALSO_BOGUS",
            ),
        )
        val result = manager.importFrom(opener(containerOf(backup)), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
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
            val backup = NyxBackup(layout = layout.toDto(), prefs = NyxBackupPrefs(iconStyle = "MONOCHROME"))

            val result = manager.importFrom(opener(containerOf(backup)), ImportOptions())

            assertThat(result).isInstanceOf(ImportResult.Error::class.java)
            coVerify(exactly = 0) { homeLayoutRepository.save(any()) } // layout write never reached
        }

    private companion object {
        /** Placeholder in a test layer's imageFileName: `#<i>` → hash of blob i. */
        const val BLOB_REF = "#"
    }
}
