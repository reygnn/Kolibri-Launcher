package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.kolibri_launcher.core.AppConstants
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
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRepositoryImplIoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var mockContext: Context
    @MockK
    private lateinit var mockContentResolver: ContentResolver
    private val mockPfd: ParcelFileDescriptor = mockk(relaxed = true)
    private val mockWallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    private lateinit var backupManager: BackupRepositoryImpl

    private val testUri = Uri.parse("content://com.android.external/file/123")

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { mockContext.contentResolver } returns mockContentResolver

        every { mockPfd.statSize } returns 1024L
        every { mockPfd.fileDescriptor } returns FileDescriptor()

        backupManager = BackupRepositoryImpl(
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

    @Test
    fun `loadBackupFromFile - file not found (returns null stream) - returns Error`() = runTest {
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } returns mockPfd
        every { mockContentResolver.openInputStream(testUri) } returns null

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Cannot read")
    }

    @Test
    fun `loadBackupFromFile - SecurityException (permission revoked) - returns Error`() = runTest {
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } throws
                SecurityException("Permission denied")
        every { mockContentResolver.openInputStream(testUri) } throws
                SecurityException("Permission denied")

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Failed to load")
    }

    @Test
    fun `loadBackupFromFile - IOException during read (disk failure) - returns Error`() = runTest {
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } returns mockPfd

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

    @Test
    fun `loadBackupFromFile - File too large (DoS protection) - returns Error`() = runTest {
        every { mockPfd.statSize } returns AppConstants.MAX_BACKUP_SIZE_BYTES + 1
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } returns mockPfd

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("too large")
    }

    @Test
    fun `loadBackupFromFile - Empty file (0 bytes) - returns InvalidFormat`() = runTest {
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } returns mockPfd
        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `loadBackupFromFile - Invalid URI string - returns Error`() = runTest {
        val result = backupManager.loadBackupFromFile("::invalid::uri", ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
    }
}