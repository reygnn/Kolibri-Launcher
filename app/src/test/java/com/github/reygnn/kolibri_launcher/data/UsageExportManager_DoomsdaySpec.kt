package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertIs

/**
 * DOOMSDAY EDITION (I/O & System Failure)
 * * Testet das Verhalten bei katastrophalen Fehlern des Dateisystems oder des Betriebssystems.
 * * Nutzt Robolectric, um Android-Klassen wie Uri und ContentResolver zu simulieren.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE) // Wir brauchen kein Manifest für diesen Unit Test
class UsageExportManager_DoomsdaySpec {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var mockContentResolver: ContentResolver
    @Mock
    private lateinit var mockPfd: ParcelFileDescriptor

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var manager: UsageExportManager

    // Eine valide Uri für Tests (Robolectric kann das parsen)
    private val testUriString = "content://com.android.external/file/123"
    private val testUri = Uri.parse(testUriString)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Grundlegendes ContentResolver Mocking
        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)

        // PFD Mocking für Size Checks (Standard: klein genug)
        `when`(mockPfd.statSize).thenReturn(1024L)
        `when`(mockPfd.fileDescriptor).thenReturn(FileDescriptor())

        fakeDataStore = FakeDataStore()
        manager = UsageExportManager(fakeDataStore, mockContext)
    }

    // ============================================================================================
    // LOAD FROM FILE SCENARIOS (Import)
    // ============================================================================================

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `doomsday - load - The Blob (File too large DoS attack)`() = runTest {
        // SZENARIO: User wählt eine 1GB Datei aus.
        // ZIEL: Verhindern, dass wir die Datei überhaupt lesen und einen OOM riskieren.

        // Wir simulieren eine Größe, die das Limit um 1 Byte überschreitet
        val hugeSize = AppConstants.MAX_BACKUP_SIZE_BYTES + 1
        `when`(mockPfd.statSize).thenReturn(hugeSize)

        // Wenn nach der Größe gefragt wird, geben wir den Mock zurück
        `when`(
            mockContentResolver.openFileDescriptor(
                ArgumentMatchers.eq(testUri),
                ArgumentMatchers.any()
            )
        ).thenReturn(mockPfd)

        val result = manager.loadFromFile(testUriString, false)

        // Erwartung: Error wegen Dateigröße
        assertIs<UsageImportResult.Error>(result)
        Assert.assertTrue(result.message.contains("too large", ignoreCase = true))
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `doomsday - load - The Vanishing Act (File deleted before read)`() = runTest {
        // SZENARIO: Datei existiert beim Auswählen, aber wird gelöscht bevor der Stream öffnet.
        // openInputStream gibt null zurück, wenn die Datei nicht gefunden wird.

        // Size Check passiert vielleicht noch...
        `when`(
            mockContentResolver.openFileDescriptor(
                ArgumentMatchers.eq(testUri),
                ArgumentMatchers.any()
            )
        ).thenReturn(mockPfd)
        // ...aber der Stream ist null
        `when`(mockContentResolver.openInputStream(ArgumentMatchers.eq(testUri)))
            .thenReturn(null)

        val result = manager.loadFromFile(testUriString, false)

        // Erwartung: Error wegen nicht lesbarer Datei
        assertIs<UsageImportResult.Error>(result)
        assertTrue(result.message.contains("Cannot read", ignoreCase = true))
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `doomsday - load - The Firewall (Permission Denied SecurityException)`() = runTest {
        // SZENARIO: App verliert Berechtigung.

        // FIX: Wir müssen BEIDE Aufrufe mocken.
        // 1. openFileDescriptor wird im OOM-Check aufgerufen (und dort ignoriert/gefangen)
        `when`(mockContentResolver.openFileDescriptor(eq(testUri), any()))
            .thenThrow(SecurityException("Permission denied by OS"))

        // 2. openInputStream ist der "echte" Leseversuch, der fehlschlagen muss
        `when`(mockContentResolver.openInputStream(eq(testUri)))
            .thenThrow(SecurityException("Permission denied by OS"))

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
        // Jetzt sollte die Message vom openInputStream catch kommen
        assertTrue(result.message.contains("Permission denied", ignoreCase = true))
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `doomsday - load - The Broken Disk (IOException mid-read)`() = runTest {
        // SZENARIO: Der Stream öffnet sich, aber mittendrin gibt es einen I/O Fehler.
        // Wir bauen einen InputStream, der beim Lesen explodiert.

        val brokenStream = object : ByteArrayInputStream(ByteArray(10)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                throw IOException("Disk sector corrupted")
            }
        }

        // Size Check ok
        `when`(
            mockContentResolver.openFileDescriptor(
                ArgumentMatchers.eq(testUri),
                ArgumentMatchers.any()
            )
        ).thenReturn(mockPfd)
        // Stream ist broken
        `when`(mockContentResolver.openInputStream(ArgumentMatchers.eq(testUri)))
            .thenReturn(brokenStream)

        val result = manager.loadFromFile(testUriString, false)

        // Erwartung: Error, der die IOException Message enthält
        assertIs<UsageImportResult.Error>(result)
        assertTrue(result.message.contains("Disk sector corrupted"))
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `doomsday - load - The Garbage URI (Invalid input)`() = runTest {
        val garbageUri = "://this-is-not-a-uri"

        val result = manager.loadFromFile(garbageUri, false)

        assertIs<UsageImportResult.Error>(result)

        // Android parst fast alles, daher scheitert es oft erst beim Öffnen ("Cannot read file")
        // oder beim allgemeinen Crash-Handling ("Load failed").
        assertTrue(
            "Expected failure message, got: ${result.message}",
            result.message.contains("Invalid file format") ||
                    result.message.contains("Load failed") ||
                    result.message.contains("Cannot read file")
        )
    }

    // ============================================================================================
    // SAVE TO FILE SCENARIOS (Export)
    // ============================================================================================

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `doomsday - save - The Full Disk (IOException on write)`() = runTest {
        // SZENARIO: Speicher ist voll während des Schreibens.

        // Ein OutputStream, der beim Schreiben explodiert
        val brokenOutputStream = Mockito.mock(OutputStream::class.java)
        `when`(brokenOutputStream.write(ArgumentMatchers.any<ByteArray>()))
            .thenThrow(IOException("No space left on device"))

        // ContentResolver gibt diesen Stream zurück
        `when`(mockContentResolver.openOutputStream(ArgumentMatchers.eq(testUri)))
            .thenReturn(brokenOutputStream)

        val success = manager.saveToFile(testUriString)

        // Erwartung: false (Fehler wurde geloggt)
        Assert.assertFalse("Save should fail on IOException", success)
    }

    @Ignore("Fails on GitHub due to missing SDK version 36")
    @Test
    fun `doomsday - save - The Locked File (Cannot open output stream)`() = runTest {
        // SZENARIO: Datei ist schreibgeschützt oder gesperrt. openOutputStream gibt null zurück.

        `when`(mockContentResolver.openOutputStream(ArgumentMatchers.eq(testUri)))
            .thenReturn(null)

        val success = manager.saveToFile(testUriString)

        // Erwartung: false
        Assert.assertFalse("Save should fail if stream is null", success)
    }
}