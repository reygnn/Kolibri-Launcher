package com.github.reygnn.kolibri_launcher.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.OutputStream

/**
 * Unit tests for [WallpaperCompositeStore]'s write-then-swap orchestration
 * (AUDIT-20 F1/F4): the temp-write → atomic-rename → delete-old ordering and the
 * checked `compress()` return. The [Bitmap] is mocked so `compress()` is fully
 * controllable — success writes a few bytes and returns true, failure returns
 * false without throwing. That is exactly the F4 case (a `false` return with no
 * exception) which a JVM/Robolectric test can pin without a real encoder; real
 * WEBP encoding parity lives in the instrumented flatten test, not here.
 *
 * Uses Robolectric so `Uri.fromFile(...)` produces a parseable URI, plus
 * [TemporaryFolder] as the backing `filesDir` so the tests hit an actual
 * filesystem directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperCompositeStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var context: Context
    private lateinit var store: WallpaperCompositeStore
    private lateinit var compositeDir: File

    @Before
    fun setUp() {
        context = mockk()
        every { context.filesDir } returns tempFolder.root
        store = WallpaperCompositeStore(context)
        compositeDir = File(tempFolder.root, WallpaperCompositeStore.COMPOSITE_DIR)
    }

    /** A bitmap whose `compress()` writes some bytes and returns [success]. */
    private fun bitmap(success: Boolean): Bitmap = mockk {
        every { compress(any(), any(), any()) } answers {
            if (success) thirdArg<OutputStream>().write(byteArrayOf(1, 2, 3))
            success
        }
    }

    /** All composite artifacts on disk, including any stray temp files. */
    private fun compositeFiles(): List<File> =
        compositeDir.listFiles { f -> f.name.startsWith("composite_") }?.toList() ?: emptyList()

    private fun fileBehind(uri: String): File = File(uri.toUri().path!!)

    @Test
    fun `write persists exactly one composite and returns its file uri`() = runTest {
        val uri = store.write(bitmap(success = true))

        assertNotNull(uri)
        assertTrue("uri must be a file:// URI", uri!!.startsWith("file://"))
        val files = compositeFiles()
        assertEquals("exactly one composite on disk", 1, files.size)
        assertTrue("returned uri points at the composite", fileBehind(uri).exists())
        assertFalse("no temp file left behind", files.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `write returns null and leaves no file when compress fails without throwing`() = runTest {
        // AUDIT-20 F4: compress() == false must not yield a path to a partial file.
        val uri = store.write(bitmap(success = false))

        assertNull(uri)
        assertTrue("no partial composite or temp file survives", compositeFiles().isEmpty())
    }

    @Test
    fun `a second successful write replaces the previous composite`() = runTest {
        val first = store.write(bitmap(success = true))
        val second = store.write(bitmap(success = true))

        assertNotNull(second)
        assertNotEquals("versioned name — a new write is a new path", first, second)
        assertEquals("only the latest composite is kept", 1, compositeFiles().size)
        assertFalse("previous composite deleted", fileBehind(first!!).exists())
        assertTrue("new composite present", fileBehind(second!!).exists())
    }

    @Test
    fun `a failed write keeps the previous composite intact`() = runTest {
        // AUDIT-20 F1: the old composite is dropped only AFTER a new one lands. A
        // failed write must therefore leave the previous, still-referenced composite
        // untouched rather than unlinking it up front.
        val good = store.write(bitmap(success = true))
        assertNotNull(good)

        val failed = store.write(bitmap(success = false))

        assertNull(failed)
        assertEquals("still exactly one composite", 1, compositeFiles().size)
        assertTrue("previous composite survives the failed write", fileBehind(good!!).exists())
    }

    @Test
    fun `clear removes the composite`() = runTest {
        store.write(bitmap(success = true))

        store.clear()

        assertTrue(compositeFiles().isEmpty())
    }
}
