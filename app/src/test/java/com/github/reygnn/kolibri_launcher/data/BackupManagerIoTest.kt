package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.IOException

/**
 * I/O Torture Tests.
 *
 * Prüft das Verhalten bei Dateisystem-Fehlern, die VOR dem JSON-Parsing passieren.
 * Läuft mit Robolectric, damit Android-Klassen wie Uri.parse() funktionieren.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupManagerIoTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkEnvironment() {
            org.junit.Assume.assumeTrue(
                "Skipping Robolectric IO tests in GitHub CI (SDK 36 not supported yet)",
                System.getenv("CI") == null
            )
        }
    }

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var mockContext: Context
    @MockK
    private lateinit var mockContentResolver: ContentResolver
    @MockK
    private lateinit var mockPfd: ParcelFileDescriptor

    private val mockWallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    private lateinit var backupManager: BackupManager
    private val testDispatcher = StandardTestDispatcher()

    private val testUri = Uri.parse("content://com.android.external/file/123")

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { mockContext.contentResolver } returns mockContentResolver

        every { mockPfd.statSize } returns 1024L
        every { mockPfd.fileDescriptor } returns FileDescriptor()

        backupManager = BackupManager(
            favoritesManager = FakeFavoritesRepository(),
            favoritesOrderManager = FakeFavoritesOrderRepository(),
            appVisibilityManager = FakeHiddenAppsRepository(),
            appNamesManager = FakeCustomNamesRepository(),
            installedAppsManager = FakeInstalledAppsRepository(),
            swipeActionsManager = FakeSwipeActionsRepository(),
            settingsManager = FakeSettingsRepository(),
            wallpaperManager = FakeWallpaperRepository(),
            wallpaperFileManager = mockWallpaperFileManager,
            context = mockContext
        )
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `loadBackupFromFile - file not found (returns null stream) - returns Error`() = runTest(testDispatcher) {
        every { mockContentResolver.openFileDescriptor(eq(testUri), eq("r")) } returns mockPfd
        every { mockContentResolver.openInputStream(testUri) } returns null

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Cannot read")
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `loadBackupFromFile - SecurityException (permission revoked) - returns Error`() = runTest(testDispatcher) {
        every { mockContentResolver.openFileDescriptor(eq(testUri), eq("r")) } throws
                SecurityException("Permission denied")
        every { mockContentResolver.openInputStream(testUri) } throws
                SecurityException("Permission denied")

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Failed to load")
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `loadBackupFromFile - IOException during read (disk failure) - returns Error`() = runTest(testDispatcher) {
        every { mockContentResolver.openFileDescriptor(eq(testUri), eq("r")) } returns mockPfd

        val boomStream = object : ByteArrayInputStream(ByteArray(0)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                throw IOException("Disk on fire")
            }
        }
        every { mockContentResolver.openInputStream(testUri) } returns boomStream

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Disk on fire")
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `loadBackupFromFile - File too large (DoS protection) - returns Error`() = runTest(testDispatcher) {
        every { mockPfd.statSize } returns 15 * 1024 * 1024L
        every { mockContentResolver.openFileDescriptor(eq(testUri), eq("r")) } returns mockPfd

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("too large")
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `loadBackupFromFile - Empty file (0 bytes) - returns InvalidFormat`() = runTest(testDispatcher) {
        every { mockContentResolver.openFileDescriptor(eq(testUri), eq("r")) } returns mockPfd
        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `loadBackupFromFile - Invalid URI string - returns Error`() = runTest(testDispatcher) {
        val result = backupManager.loadBackupFromFile("::invalid::uri", ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
    }
}
