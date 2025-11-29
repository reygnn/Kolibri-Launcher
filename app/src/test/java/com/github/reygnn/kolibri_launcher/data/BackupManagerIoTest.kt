package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.fakes.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException

/**
 * I/O Torture Tests.
 *
 * Prüft das Verhalten bei Dateisystem-Fehlern, die VOR dem JSON-Parsing passieren.
 * (z.B. Datei gelöscht, SD-Karte entfernt, Berechtigung entzogen).
 *
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

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockContentResolver: ContentResolver

    // Mocks für FileDescriptor (Größen-Check)
    @Mock
    private lateinit var mockPfd: ParcelFileDescriptor

    private lateinit var backupManager: BackupManager
    private val testDispatcher = StandardTestDispatcher()

    // Leere Fakes reichen hier, da wir meistens Errors erwarten
    private val fakeRepo = FakeSettingsRepository()

    // Robolectric sorgt dafür, dass Uri.parse() hier ein echtes Objekt zurückgibt
    private val testUri = Uri.parse("content://com.android.external/file/123")

    @Before
    fun setup() {
        // Wichtig: Bei Robolectric müssen Mocks manuell initialisiert werden
        MockitoAnnotations.openMocks(this)

        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)

        // Standard: File Size Check Mocking (sagen wir 1KB)
        val fd = FileDescriptor()
        `when`(mockPfd.statSize).thenReturn(1024L)
        `when`(mockPfd.fileDescriptor).thenReturn(fd)

        // Setup BackupManager (nutzt Fakes für Repos)
        backupManager = BackupManager(
            FakeFavoritesRepository(),
            FakeFavoritesOrderRepository(),
            FakeHiddenAppsRepository(),
            FakeCustomNamesRepository(),
            FakeInstalledAppsRepository(),
            FakeSwipeActionsRepository(),
            fakeRepo,
            mockContext
        )
    }

    @Test
    fun `loadBackupFromFile - file not found (returns null stream) - returns Error`() = runTest(testDispatcher) {
        // SCENARIO: Datei wurde zwischen Auswahl und Lesen gelöscht.
        // openInputStream liefert null bei "File not found"
        `when`(mockContentResolver.openFileDescriptor(eq(testUri), eq("r"))).thenReturn(mockPfd)
        `when`(mockContentResolver.openInputStream(testUri)).thenReturn(null)

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        // Error: "Cannot read from selected location"
        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Cannot read")
    }

    @Test
    fun `loadBackupFromFile - SecurityException (permission revoked) - returns Error`() = runTest(testDispatcher) {
        // SCENARIO: User entzieht Rechte während die App läuft.
        // openFileDescriptor wirft Exception (wird intern für Size-Check gefangen)
        `when`(mockContentResolver.openFileDescriptor(eq(testUri), eq("r"))).thenThrow(SecurityException("Permission denied"))
        // openInputStream muss AUCH werfen, damit der äußere try-catch Block greift
        `when`(mockContentResolver.openInputStream(testUri)).thenThrow(SecurityException("Permission denied"))

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Failed to load")
    }

    @Test
    fun `loadBackupFromFile - IOException during read (disk failure) - returns Error`() = runTest(testDispatcher) {
        // SCENARIO: Stream öffnet, aber Lesen schlägt fehl (z.B. Korrupter Sektor).
        `when`(mockContentResolver.openFileDescriptor(eq(testUri), eq("r"))).thenReturn(mockPfd)

        // Ein Stream, der beim Lesen explodiert
        val boomStream = object : ByteArrayInputStream(ByteArray(0)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                throw IOException("Disk on fire")
            }
        }
        `when`(mockContentResolver.openInputStream(testUri)).thenReturn(boomStream)

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Disk on fire")
    }

    @Test
    fun `loadBackupFromFile - File too large (DoS protection) - returns Error`() = runTest(testDispatcher) {
        // SCENARIO: User wählt eine 500MB Datei aus (OOM Attacke)
        // Wir simulieren 15MB (Limit ist 10MB)
        `when`(mockPfd.statSize).thenReturn(15 * 1024 * 1024L)
        `when`(mockContentResolver.openFileDescriptor(eq(testUri), eq("r"))).thenReturn(mockPfd)

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        // Sollte direkt ablehnen ohne zu lesen
        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("too large")
    }

    @Test
    fun `loadBackupFromFile - Empty file (0 bytes) - returns InvalidFormat`() = runTest(testDispatcher) {
        // SCENARIO: Datei existiert, ist aber leer.
        `when`(mockContentResolver.openFileDescriptor(eq(testUri), eq("r"))).thenReturn(mockPfd)
        `when`(mockContentResolver.openInputStream(testUri)).thenReturn(ByteArrayInputStream(ByteArray(0)))

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        // BackupManager prüft: if (jsonString.isBlank()) return InvalidFormat
        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `loadBackupFromFile - Invalid URI string - returns Error`() = runTest(testDispatcher) {
        // SCENARIO: URI ist Quatsch
        val result = backupManager.loadBackupFromFile("::invalid::uri", ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
    }
}