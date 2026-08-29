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
import java.io.InputStream
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
    private lateinit var context: Context
    @MockK
    private lateinit var contentResolver: ContentResolver
    @MockK
    private lateinit var parcelFileDescriptor: ParcelFileDescriptor

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var manager: UsageExportRepositoryImpl

    private val testUriString = "content://com.android.external/file/123"
    private val testUri = Uri.parse(testUriString)

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { context.contentResolver } returns contentResolver
        every { parcelFileDescriptor.statSize } returns 1024L
        every { parcelFileDescriptor.fileDescriptor } returns FileDescriptor()

        fakeDataStore = FakeDataStore()
        manager = UsageExportRepositoryImpl(fakeDataStore, context, "test-version")
    }

    // ============================================================================================
    // LOAD FROM FILE SCENARIOS (Import)
    // ============================================================================================

    @Test
    fun `doomsday - load - The Blob (File too large DoS attack)`() = runTest {
        val hugeSize = AppConstants.MAX_BACKUP_SIZE_BYTES + 1
        every { parcelFileDescriptor.statSize } returns hugeSize
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
    }

    @Test
    fun `load - unknown file size (statSize -1) with valid small content still imports`() = runTest {
        // RC edge-case audit #7 regression guard: streaming/pipe providers report
        // statSize == -1 (unknown). The fast-path size check must NOT reject them
        // — real small files can report -1/0 — so a legitimate small export still
        // imports. Guards against a naive "reject on non-positive statSize" fix.
        every { parcelFileDescriptor.statSize } returns -1L
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor

        val validTs = System.currentTimeMillis() - 10_000
        val json = """{"version":"1.0.0","usage_data":{"com.test":["$validTs"]}}"""
        every { contentResolver.openInputStream(eq(testUri)) } returns
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Success>(result)
    }

    @Test
    fun `load - unknown size + exactly one byte over the cap is rejected as too large`() = runTest {
        // #7 boundary: statSize unknown (-1) bypasses the fast path, and the stream
        // is EXACTLY cap+1 bytes. Pins the read width readNBytes(cap+1): a
        // readNBytes(cap) off-by-one would read only cap bytes here, see size == cap,
        // and wrongly import instead of rejecting.
        every { parcelFileDescriptor.statSize } returns -1L
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        val cap = AppConstants.MAX_BACKUP_SIZE_BYTES
        every { contentResolver.openInputStream(eq(testUri)) } returns fixedSizeStream(cap + 1)

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
        assertTrue(result.message.contains("too large", ignoreCase = true))
    }

    @Test
    fun `load - unknown size + exactly the cap in bytes passes the size gate`() = runTest {
        // #7 boundary: EXACTLY cap bytes must NOT be rejected — it passes the
        // `> cap` gate and reaches the parser (non-JSON here → InvalidFormat). Pins
        // `> cap` against a `>= cap` off-by-one that would wrongly reject a
        // legitimate cap-sized file as "File too large".
        every { parcelFileDescriptor.statSize } returns -1L
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        val cap = AppConstants.MAX_BACKUP_SIZE_BYTES
        every { contentResolver.openInputStream(eq(testUri)) } returns fixedSizeStream(cap)

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `doomsday - load - The Vanishing Act (File deleted before read)`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        every { contentResolver.openInputStream(eq(testUri)) } returns null

        val result = manager.loadFromFile(testUriString, false)

        assertIs<UsageImportResult.Error>(result)
        assertTrue(result.message.contains("Cannot read", ignoreCase = true))
    }

    @Test
    fun `doomsday - load - The Firewall (Permission Denied SecurityException)`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } throws
                SecurityException("Permission denied by OS")
        every { contentResolver.openInputStream(eq(testUri)) } throws
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

        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        every { contentResolver.openInputStream(eq(testUri)) } returns brokenStream

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
        every { contentResolver.openOutputStream(eq(testUri)) } returns brokenOutputStream

        val success = manager.saveToFile(testUriString)

        Assert.assertFalse("Save should fail on IOException", success)
    }

    @Test
    fun `doomsday - save - The Locked File (Cannot open output stream)`() = runTest {
        every { contentResolver.openOutputStream(eq(testUri)) } returns null

        val success = manager.saveToFile(testUriString)

        Assert.assertFalse("Save should fail if stream is null", success)
    }

    /**
     * An [InputStream] yielding exactly [byteCount] bytes of `'a'`, then EOF — a
     * lightweight fixed-size source for the bounded-read boundary tests (no
     * byteCount-sized literal; `readNBytes` accumulates it in chunks).
     */
    private fun fixedSizeStream(byteCount: Long): InputStream = object : InputStream() {
        private var remaining = byteCount
        override fun read(): Int = if (remaining-- > 0) 'a'.code else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            b.fill('a'.code.toByte(), off, off + n)
            remaining -= n
            return n
        }
    }
}