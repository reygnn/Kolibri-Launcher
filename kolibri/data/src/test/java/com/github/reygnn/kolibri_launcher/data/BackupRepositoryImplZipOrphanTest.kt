package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP import never leaves extracted wallpaper images orphaned in internal storage (port of nyx
 * §Audit-3 A3-05): every extracted image the restored wallpaper does not claim is deleted on
 * every path, images are not even extracted when the wallpaper isn't being imported, and the
 * restore claims its images BEFORE saving so an interrupted save never deletes referenced files.
 *
 * Robolectric for real [Uri] parsing; the file manager is a mock (extraction/copy/delete are
 * observed, not performed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRepositoryImplZipOrphanTest {

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

    /** Serves [zipBytes] for the backup URI (fresh per call: isZipFile + import) and a readable stream for every extracted image. */
    private fun serve(zipBytes: ByteArray) {
        every { contentResolver.openInputStream(any()) } answers {
            if (firstArg<Uri>() == backupUri) ByteArrayInputStream(zipBytes) else ByteArrayInputStream(byteArrayOf(1))
        }
    }

    private fun zipOf(manifest: String, vararg images: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            images.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** A valid manifest whose wallpaper has one layer backed by the ZIP entry `wallpapers/layer_0.img`. */
    private fun oneLayerManifest(): String = BackupSerializer().encodeToJsonString(
        BackupData(
            version = AppConstants.BACKUP_VERSION,
            timestamp = 1L,
            settings = LauncherSettings(
                wallpaperLayers = listOf(WallpaperLayerBackup(id = "l0", imageFileName = "wallpapers/layer_0.img")),
            ),
        ),
    )

    @Test
    fun `a restored layer keeps its image and an unreferenced image is deleted`() = runTest {
        serve(zipOf(oneLayerManifest(), "wallpapers/layer_0.img", "wallpapers/stray.img"))
        val wallpaperRepository = FakeWallpaperRepository()

        val result = repository(wallpaperRepository).loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(wallpaperRepository.currentState.layers.single().imageUri).isEqualTo(extracted0)
        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted0) }
        verify { wallpaperFileManager.deleteFile(extracted1) }
    }

    @Test
    fun `an invalid manifest deletes the already-extracted images`() = runTest {
        serve(zipOf("{ not json", "wallpapers/layer_0.img"))

        val result = repository().loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
        verify { wallpaperFileManager.deleteFile(extracted0) }
    }

    @Test
    fun `an over-sized image deletes the images extracted before it`() = runTest {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("wallpapers/layer_0.img"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wallpapers/big.img"))
            zip.write(ByteArray(11 * 1024 * 1024)) // > the per-blob cap, compresses tiny
            zip.closeEntry()
        }
        serve(bos.toByteArray())

        val result = repository().loadBackupFromFile(backupUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        verify { wallpaperFileManager.deleteFile(extracted0) }
    }

    @Test
    fun `images are not extracted when the wallpaper is not being imported`() = runTest {
        serve(zipOf(oneLayerManifest(), "wallpapers/layer_0.img"))

        repository().loadBackupFromFile(backupUri.toString(), ImportOptions(importWallpaper = false))

        verify(exactly = 0) { wallpaperFileManager.copyFromInputStream(any()) }
    }

    @Test
    fun `an interrupted wallpaper save keeps the claimed image`() = runTest {
        // DataStore can commit and the call still end in a CancellationException: the image was
        // claimed BEFORE the save, so it must survive (at worst an orphan for gcOrphans).
        serve(zipOf(oneLayerManifest(), "wallpapers/layer_0.img"))
        val interrupted = mockk<WallpaperRepository>(relaxed = true)
        coEvery { interrupted.saveWallpaperState(any()) } throws CancellationException("left the screen")

        runCatching { repository(interrupted).loadBackupFromFile(backupUri.toString(), ImportOptions()) }

        verify(exactly = 0) { wallpaperFileManager.deleteFile(extracted0) }
    }
}
