package com.github.reygnn.kolibri_launcher.data

import kotlin.test.assertFailsWith
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.launcher.core.wallpaper.FakeWallpaperRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.InputStream

/**
 * Container import (SPEC_NYX_REWRITE 2a-6; formerly the ZIP-orphan test, §Audit-3 A3-05) never
 * leaves wallpaper images orphaned in internal storage: only blobs a layer references are ever
 * copied in, every copy the restored wallpaper does not claim is deleted, nothing is copied
 * when the wallpaper isn't imported or the backup is refused, and the restore claims its images
 * BEFORE saving so an interrupted save never deletes referenced files. Every restored layer
 * gets a file of its own, also when two layers share one blob (O2).
 *
 * Robolectric for real [Uri] parsing; the file manager is a mock (extraction/copy/delete are
 * observed, not performed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRepositoryImplContainerOrphanTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = mockk()
    private val contentResolver: ContentResolver = mockk()
    private val parcelFileDescriptor: ParcelFileDescriptor = mockk(relaxed = true)
    private val wallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    private val backupUri = Uri.parse("content://com.android.external/file/backup")
    private val extracted0 = "file:///data/wallpapers/wp_0"
    private val extracted1 = "file:///data/wallpapers/wp_1"

    @Before
    fun setup() {
        every { context.contentResolver } returns contentResolver
        every { parcelFileDescriptor.statSize } returns 1024L
        every { parcelFileDescriptor.fileDescriptor } returns FileDescriptor()
        every { contentResolver.openFileDescriptor(eq(backupUri), any()) } returns parcelFileDescriptor
        every { wallpaperFileManager.copyFromInputStream(any()) } answers {
            firstArg<InputStream>().readBytes(); Uri.parse(extracted0)
        } andThenAnswer {
            firstArg<InputStream>().readBytes(); Uri.parse(extracted1)
        }
        // Real copyToInternal returns an already-internal URI unchanged (the ZIP-extracted case).
        coEvery { wallpaperFileManager.copyToInternal(any()) } answers { firstArg() }
    }

    private fun repository(wallpaperRepository: WallpaperRepository = FakeWallpaperRepository()) =
        BackupRepositoryImplTestFactory.create(
            favoritesRepository = FakeFavoritesRepository(),
            favoritesOrderRepository = FakeFavoritesOrderRepository(),
            hiddenAppsRepository = FakeHiddenAppsRepository(),
            customNamesRepository = FakeCustomNamesRepository(),
            // Cold-path gate: performImport waits for a non-empty installed-apps emission.
            installedAppsRepository = FakeInstalledAppsRepository().apply {
                installedApps = listOf(
                    AppInfo(
                        originalName = "Sentinel",
                        displayName = "Sentinel",
                        packageName = "kolibri.test.sentinel",
                        className = "kolibri.test.sentinel.Main",
                    ),
                )
            },
            swipeActionsRepository = FakeSwipeActionsRepository(),
            settingsRepository = FakeSettingsRepository(),
            wallpaperRepository = wallpaperRepository,
            wallpaperFileManager = wallpaperFileManager,
            context = context,
        )

    /** Serves [zipBytes] for the backup URI (fresh per call) and a readable stream for every extracted image. */
    private fun serve(zipBytes: ByteArray) {
        every { contentResolver.openInputStream(any()) } answers {
            if (firstArg<Uri>() == backupUri) ByteArrayInputStream(zipBytes) else ByteArrayInputStream(byteArrayOf(1))
        }
    }

    private val engine = BackupEngine(Dispatchers.IO, emptySet())
    private val imageA = byteArrayOf(1, 2, 3)
    private val imageB = byteArrayOf(4, 5, 6)

    /** A current-format backup; [settings] gets each blob's hash so its layers can reference them. */
    private suspend fun container(
        vararg blobs: ByteArray,
        section: JsonElement? = null,
        settings: (List<String>) -> LauncherSettings = ::oneLayer,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        engine.export(
            output = out,
            producer = ContainerManifest.Producer(KolibriBackupSchema.APP_ID, "test", 1L),
            schemaVersion = KolibriBackupSchema.SCHEMA_VERSION,
            blobs = blobs.map { bytes -> BlobSource("image/*") { ByteArrayInputStream(bytes) } },
        ) { hashes ->
            val data = section ?: BackupSerializer().settingsToJson(settings(hashes))
            mapOf(KolibriBackupSchema.SECTION_BACKUP to ContainerManifest.Section(KolibriBackupSchema.SECTION_VERSION, data))
        }
        return out.toByteArray()
    }

    /** One wallpaper layer backed by the first blob. */
    private fun oneLayer(hashes: List<String>) =
        LauncherSettings(wallpaperLayers = listOf(WallpaperLayerBackup(id = "l0", imageFileName = hashes[0])))

    @Test
    fun `a restored layer keeps its image and an unreferenced blob is never copied in`() = runTest {
        serve(container(imageA, imageB)) // imageB is in the backup, but no layer references it
        val wallpaperRepository = FakeWallpaperRepository()

        val result = repository(wallpaperRepository).loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(wallpaperRepository.currentState.layers.single().imageUri).isEqualTo(extracted0)
        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted0) }
        verify(exactly = 1) { wallpaperFileManager.copyFromInputStream(any()) }
    }

    @Test
    fun `an invalid section copies nothing in`() = runTest {
        serve(container(imageA, section = JsonPrimitive("not settings")))

        val result = repository().loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
        verify(exactly = 0) { wallpaperFileManager.copyFromInputStream(any()) }
    }

    @Test
    fun `an over-sized blob is refused before anything is copied in`() = runTest {
        serve(container(imageA, ByteArray(11 * 1024 * 1024))) // > the per-blob cap, compresses tiny

        val result = repository().loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        verify(exactly = 0) { wallpaperFileManager.copyFromInputStream(any()) }
    }

    @Test
    fun `images are not copied in when the wallpaper is not being imported`() = runTest {
        serve(container(imageA))
        repository().loadBackupFromFile(backupUri.toString(), ImportOptions(importWallpaper = false))
        verify(exactly = 0) { wallpaperFileManager.copyFromInputStream(any()) }
    }

    @Test
    fun `an interrupted wallpaper save keeps the claimed image`() = runTest {
        // DataStore can commit and the call still end in a CancellationException: the image was
        // claimed BEFORE the save, so it must survive (at worst an orphan for gcOrphans).
        serve(container(imageA))
        val interrupted = mockk<WallpaperRepository>(relaxed = true)
        coEvery { interrupted.saveWallpaperState(any()) } throws CancellationException("left the screen")
        runCatching { repository(interrupted).loadBackupFromFile(backupUri.toString(), ImportOptions()) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted0) }
    }

    // ---- cleanup through the store: only unclaimed copies (3a-7) ----

    /** One layer on the first blob; the old single-image field names the second, which no layer restores. */
    private fun layerPlusUnusedSingle(hashes: List<String>) = LauncherSettings(
        wallpaperLayers = listOf(WallpaperLayerBackup(id = "l0", imageFileName = hashes[0])),
        wallpaperImageFileName = hashes[1],
    )

    @Test
    fun `a silently failed wallpaper save keeps the claimed copy and drops only the unclaimed one`() = runTest {
        // The claimed copy is not in the persisted state after the swallowed save; handing it to
        // the cleanup would delete it. Only the unclaimed copy may go.
        serve(container(imageA, imageB, settings = ::layerPlusUnusedSingle))
        val wallpaperRepository = FakeWallpaperRepository().apply { failSavesSilently = true }

        repository(wallpaperRepository).loadBackupFromFile(backupUri.toString(), ImportOptions())

        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted0) } // claimed: orphan for the GC at worst
        verify(exactly = 1) { wallpaperFileManager.deleteFile(extracted1) } // unclaimed: through the store
    }

    @Test
    fun `an interrupted import still cleans up and the cancellation propagates`() = runTest {
        serve(container(imageA, imageB, settings = ::layerPlusUnusedSingle))
        val interrupted = mockk<WallpaperRepository>(relaxed = true)
        coEvery { interrupted.saveWallpaperState(any()) } throws CancellationException("left the screen")
        coEvery { interrupted.readPersistedImageUris() } returns emptySet()

        assertFailsWith<CancellationException> {
            repository(interrupted).loadBackupFromFile(backupUri.toString(), ImportOptions())
        }

        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted0) }
        verify(exactly = 1) { wallpaperFileManager.deleteFile(extracted1) } // NonCancellable cleanup ran
    }

    // ---- one file per layer (SPEC_NYX_REWRITE O2) ----

    @Test
    fun `two layers sharing one image get one file each`() = runTest {
        // The container stores the image once and both layers resolve to the same extracted
        // file. Removing a layer deletes its file right away, so the second layer needs a copy
        // of its own — otherwise removing either layer breaks the other.
        serve(
            container(imageA) { hashes ->
                LauncherSettings(
                    wallpaperLayers = listOf(
                        WallpaperLayerBackup(id = "l0", imageFileName = hashes[0]),
                        WallpaperLayerBackup(id = "l1", imageFileName = hashes[0]),
                    ),
                )
            },
        )
        val wallpaperRepository = FakeWallpaperRepository()

        val result = repository(wallpaperRepository).loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(wallpaperRepository.currentState.layers.map { it.imageUri })
            .containsExactly(extracted0, extracted1).inOrder()
        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted0) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted1) }
    }

    @Test
    fun `layers with different images keep their own files without an extra copy`() = runTest {
        serve(
            container(imageA, imageB) { hashes ->
                LauncherSettings(
                    wallpaperLayers = listOf(
                        WallpaperLayerBackup(id = "l0", imageFileName = hashes[0]),
                        WallpaperLayerBackup(id = "l1", imageFileName = hashes[1]),
                    ),
                )
            },
        )
        val wallpaperRepository = FakeWallpaperRepository()

        repository(wallpaperRepository).loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(wallpaperRepository.currentState.layers.map { it.imageUri })
            .containsExactly(extracted0, extracted1).inOrder()
        verify(exactly = 2) { wallpaperFileManager.copyFromInputStream(any()) } // one per blob, none extra
    }

    // ---- outcomes the UI shows as their own message (2a-7) ----

    @Test
    fun `a backup written by another app is refused as ForeignBackup and copies nothing in`() = runTest {
        val out = ByteArrayOutputStream()
        engine.export(
            output = out,
            producer = ContainerManifest.Producer("nyx", "test", 1L),
            schemaVersion = 1,
            blobs = listOf(BlobSource("image/*") { ByteArrayInputStream(imageA) }),
        ) { mapOf("nyx.layout" to ContainerManifest.Section(1, JsonPrimitive("x"))) }
        serve(out.toByteArray())

        val result = repository().loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.ForeignBackup("nyx"))
        verify(exactly = 0) { wallpaperFileManager.copyFromInputStream(any()) }
    }

    // ---- the preview names the reason, the same as the import (2a-7b) ----
    // Every refusal case, preview against import, runs in KolibriBackupFormatTest (shared
    // BackupFormatContract, 2b-3c); this file keeps what the contract does not see.

    @Test
    fun `a readable backup previews as readable`() = runTest {
        serve(container(imageA))

        assertThat(repository().previewBackup(backupUri.toString())).isInstanceOf(PreviewResult.Readable::class.java)
    }
}
