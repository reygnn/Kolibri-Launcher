package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertIs

/**
 * DOOMSDAY EDITION (I/O & System Failure)
 * Nutzt Robolectric, um Android-Klassen wie Uri und ContentResolver zu simulieren.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UsageExportRepositoryImplDoomsdaySpec {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var mockContext: Context
    @MockK
    private lateinit var mockContentResolver: ContentResolver
    @MockK
    private lateinit var mockPfd: ParcelFileDescriptor

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var manager: UsageExportRepositoryImpl

    private val testUriString = "content://com.android.external/file/123"
    private val testUri = Uri.parse(testUriString)

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { mockContext.contentResolver } returns mockContentResolver
        every { mockPfd.statSize } returns 1024L
        every { mockPfd.fileDescriptor } returns FileDescriptor()

        fakeDataStore = FakeDataStore()
        manager = UsageExportRepositoryImpl(fakeDataStore, mockContext)
    }

    // ============================================================================================
    // LOAD FROM FILE SCENARIOS (Import)
    // ============================================================================================

    @Test
    fun `doomsday - load - The Blob (File too large DoS attack)`() = runTest {
        val hugeSize = AppConstants.MAX_BACKUP_SIZE_BYTES + 1
        every { mockPfd.statSize } returns hugeSize
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } returns mockPfd

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
    }

    @Test
    fun `doomsday - load - The Vanishing Act (File deleted before read)`() = runTest {
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } returns mockPfd
        every { mockContentResolver.openInputStream(eq(testUri)) } returns null

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
        assertTrue(result.message.contains("Cannot read", ignoreCase = true))
    }

    @Test
    fun `doomsday - load - The Firewall (Permission Denied SecurityException)`() = runTest {
        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } throws
                SecurityException("Permission denied by OS")
        every { mockContentResolver.openInputStream(eq(testUri)) } throws
                SecurityException("Permission denied by OS")

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
        assertTrue(result.message.contains("Permission denied", ignoreCase = true))
    }

    @Test
    fun `doomsday - load - The Broken Disk (IOException mid-read)`() = runTest {
        val brokenStream = object : ByteArrayInputStream(ByteArray(10)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                throw IOException("Disk sector corrupted")
            }
        }

        every { mockContentResolver.openFileDescriptor(eq(testUri), any()) } returns mockPfd
        every { mockContentResolver.openInputStream(eq(testUri)) } returns brokenStream

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
        assertTrue(result.message.contains("Disk sector corrupted"))
    }

    @Test
    fun `doomsday - load - The Garbage URI (Invalid input)`() = runTest {
        val result = manager.loadFromFile("://this-is-not-a-uri", false)

        assertIs<UsageImportResult.Error>(result)
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

    @Test
    fun `doomsday - save - The Full Disk (IOException on write)`() = runTest {
        val brokenOutputStream = mockk<OutputStream>()
        every { brokenOutputStream.write(any<ByteArray>()) } throws IOException("No space left on device")
        every { mockContentResolver.openOutputStream(eq(testUri)) } returns brokenOutputStream

        val success = manager.saveToFile(testUriString)

        Assert.assertFalse("Save should fail on IOException", success)
    }

    @Test
    fun `doomsday - save - The Locked File (Cannot open output stream)`() = runTest {
        every { mockContentResolver.openOutputStream(eq(testUri)) } returns null

        val success = manager.saveToFile(testUriString)

        Assert.assertFalse("Save should fail if stream is null", success)
    }
}