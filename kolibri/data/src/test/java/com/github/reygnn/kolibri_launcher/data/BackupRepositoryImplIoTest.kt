package com.github.reygnn.kolibri_launcher.data
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
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
import java.io.InputStream

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
    private lateinit var context: Context
    @MockK
    private lateinit var contentResolver: ContentResolver
    private val parcelFileDescriptor: ParcelFileDescriptor = mockk(relaxed = true)
    private val wallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    private lateinit var backupManager: BackupRepositoryImpl

    private val testUri = Uri.parse("content://com.android.external/file/123")

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { context.contentResolver } returns contentResolver

        every { parcelFileDescriptor.statSize } returns 1024L
        every { parcelFileDescriptor.fileDescriptor } returns FileDescriptor()

        backupManager = BackupRepositoryImplTestFactory.create(
            favoritesRepository = FakeFavoritesRepository(),
            favoritesOrderRepository = FakeFavoritesOrderRepository(),
            hiddenAppsRepository = FakeHiddenAppsRepository(),
            customNamesRepository = FakeCustomNamesRepository(),
            installedAppsRepository = FakeInstalledAppsRepository(),
            swipeActionsRepository = FakeSwipeActionsRepository(),
            settingsRepository = FakeSettingsRepository(),
            wallpaperRepository = FakeWallpaperRepository(),
            wallpaperFileManager = wallpaperFileManager,
            context = context
        )
    }

    @Test
    fun `loadBackupFromFile - file not found (returns null stream) - returns Error`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        every { contentResolver.openInputStream(testUri) } returns null

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Cannot read")
    }

    @Test
    fun `loadBackupFromFile - SecurityException (permission revoked) - returns Error`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } throws
                SecurityException("Permission denied")
        every { contentResolver.openInputStream(testUri) } throws
                SecurityException("Permission denied")

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Failed to load")
    }

    @Test
    fun `loadBackupFromFile - IOException during read (disk failure) - returns Error`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor

        val boomStream = object : ByteArrayInputStream(ByteArray(0)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                throw IOException("Disk on fire")
            }
        }
        every { contentResolver.openInputStream(testUri) } returns boomStream

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("Disk on fire")
    }

    @Test
    fun `loadBackupFromFile - File too large (DoS protection) - returns Error`() = runTest {
        every { parcelFileDescriptor.statSize } returns AppConstants.MAX_BACKUP_SIZE_BYTES + 1
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("too large")
    }

    // §Audit-2 N5 mirror: the ZIP blob loop is bounded so a decompression bomb can't fill the disk.

    /** A ZIP archive with the given wallpaper entries (name → decompressed bytes). */
    private fun zipWith(vararg images: Pair<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zip ->
            images.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `importFromZip - an over-sized wallpaper blob - returns Error`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor // statSize 1024 → passes the file-size gate
        // 11 MiB decompressed (compresses tiny, so the whole-archive cap is untouched) > the 10 MiB per-blob cap.
        val zipBytes = zipWith("wallpapers/big.img" to ByteArray(11 * 1024 * 1024))
        every { contentResolver.openInputStream(testUri) } answers { ByteArrayInputStream(zipBytes) } // fresh per call (isZipFile + import)
        // The real copyFromInputStream drains the stream; make the mock do the same so the per-blob cap counts.
        every { wallpaperFileManager.copyFromInputStream(any()) } answers {
            firstArg<InputStream>().readBytes(); mockk<Uri>(relaxed = true)
        }

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("too large")
    }

    @Test
    fun `importFromZip - too many wallpaper blobs - returns Error`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        val many = (0..64).map { "wallpapers/img_$it.img" to byteArrayOf(1) }.toTypedArray() // 65 > MAX_IMAGE_ENTRIES
        val zipBytes = zipWith(*many)
        every { contentResolver.openInputStream(testUri) } answers { ByteArrayInputStream(zipBytes) }

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("too many")
    }

    @Test
    fun `importFromZip - archive exceeding the whole-archive cap - returns Error`() = runTest {
        // §Audit-2 N5: the whole-archive cap bounds the total COMPRESSED bytes ZipInputStream may
        // pull — including the closeEntry skip of a non-wallpaper padding entry — which also closes
        // the statSize == -1 slip. A single incompressible > 10 MiB "pad.bin" (neither manifest nor
        // wallpaper) trips only the whole-archive cap; WITHOUT it the padding is skipped and the
        // manifest parses (a different, non-Error outcome).
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor // statSize 1024 passes the size gate
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            zip.write("{}".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("pad.bin"))
            zip.write(kotlin.random.Random(0).nextBytes(11 * 1024 * 1024)) // ~11 MiB compressed > 10 MiB budget
            zip.closeEntry()
        }
        val zipBytes = bos.toByteArray()
        every { contentResolver.openInputStream(testUri) } answers { ByteArrayInputStream(zipBytes) } // fresh per call

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
    }

    @Test
    fun `loadBackupFromFile - Empty file (0 bytes) - returns InvalidFormat`() = runTest {
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `loadBackupFromFile - Invalid URI string - returns Error`() = runTest {
        val result = backupManager.loadBackupFromFile("::invalid::uri", ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
    }

    @Test
    fun `loadBackupFromFile - unknown size statSize -1 with over-cap stream - returns Error (bounded read)`() = runTest {
        // RC edge-case audit #1: a streaming/pipe ContentProvider reports statSize == -1,
        // which slips past the `fileSize > MAX` fast-path (`-1 > MAX` is false). The read
        // must be BOUNDED (readNBytes(cap+1)) so a hostile over-cap stream is rejected
        // instead of OOMing an unbounded readText() — the hardening the usage-export path
        // already had. A FRESH stream per openInputStream call: isZipFile opens one (reads
        // the 2 magic bytes), the JSON read opens another.
        every { parcelFileDescriptor.statSize } returns -1L
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        val cap = AppConstants.MAX_BACKUP_SIZE_BYTES
        every { contentResolver.openInputStream(testUri) } answers {
            object : InputStream() {
                private var remaining = cap + 1
                override fun read(): Int = if (remaining-- > 0) 'a'.code else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0L) return -1
                    val n = minOf(len.toLong(), remaining).toInt()
                    remaining -= n
                    return n
                }
            }
        }

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("too large")
    }

    @Test
    fun `loadBackupFromFile - unknown size statSize -1 with small content is not rejected for size`() = runTest {
        // Guards against a naive "reject when statSize <= 0" fix: a provider that simply
        // does not report a size must still let a small file through. Empty content flows
        // to the normal InvalidFormat path, NOT a "too large" Error.
        every { parcelFileDescriptor.statSize } returns -1L
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        every { contentResolver.openInputStream(testUri) } answers { ByteArrayInputStream(ByteArray(0)) }

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `previewBackup - unknown size statSize -1 with over-preview-limit stream - returns null (bounded at preview limit)`() = runTest {
        // The preview path caps the statSize == -1 read at MAX_PREVIEW_SIZE_BYTES (1 MB for
        // non-ZIP), NOT the larger import cap — matching its own fast-path check. A stream
        // just past the preview limit must be rejected, so previewBackup returns null. A
        // fresh stream per openInputStream call: isZipFile reads the 2 magic bytes (leading
        // 'a' != "PK", so it is treated as JSON), then the bounded JSON read runs.
        every { parcelFileDescriptor.statSize } returns -1L
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        val previewLimit = AppConstants.MAX_PREVIEW_SIZE_BYTES
        every { contentResolver.openInputStream(testUri) } answers {
            object : InputStream() {
                private var remaining = previewLimit + 1
                override fun read(): Int = if (remaining-- > 0) 'a'.code else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0L) return -1
                    val n = minOf(len.toLong(), remaining).toInt()
                    remaining -= n
                    return n
                }
            }
        }

        val result = backupManager.previewBackup(testUri.toString())

        assertThat(result).isNull()
    }

    @Test
    fun `loadBackupFromFile - file size exactly at MAX is accepted (inclusive, not rejected for size)`() = runTest {
        // The size gate is `fileSize > MAX` (strict), so a file exactly at the limit must NOT
        // be rejected for size — only MAX+1 was tested (RC edge-case audit B9a). Empty content
        // at the boundary flows to the normal InvalidFormat path, not a "too large" Error.
        every { parcelFileDescriptor.statSize } returns AppConstants.MAX_BACKUP_SIZE_BYTES
        every { contentResolver.openFileDescriptor(eq(testUri), any()) } returns parcelFileDescriptor
        every { contentResolver.openInputStream(testUri) } answers { ByteArrayInputStream(ByteArray(0)) }

        val result = backupManager.loadBackupFromFile(testUri.toString(), ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }
}