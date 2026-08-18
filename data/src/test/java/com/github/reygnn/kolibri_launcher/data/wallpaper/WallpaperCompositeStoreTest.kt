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
    fun `write no longer prunes — a second write leaves both composites on disk`() = runTest {
        // AUDIT-20 F7: write() creates a NEW file and does NOT drop the previous one.
        // Cleanup is the delegate's job (prune / delete), deferred until it has decided
        // which path to keep — so a superseded write never unlinks a referenced file.
        val first = store.write(bitmap(success = true))
        val second = store.write(bitmap(success = true))

        assertNotNull(second)
        assertNotEquals("versioned name — a new write is a new path", first, second)
        assertEquals("write does not prune — both composites present", 2, compositeFiles().size)
        assertTrue("previous composite survives the write", fileBehind(first!!).exists())
        assertTrue("new composite present", fileBehind(second!!).exists())
    }

    @Test
    fun `prune keeps only the retained composite and sweeps the rest`() = runTest {
        // AUDIT-20 F7: after the delegate persists a path, prune(keepUri) drops every
        // OTHER composite. Also proves the stray-tmp sweep.
        val first = store.write(bitmap(success = true))
        val second = store.write(bitmap(success = true))
        val strayTmp = File(compositeDir, "composite_stray_0.webp.tmp").apply { writeBytes(byteArrayOf(9)) }

        store.prune(second!!)

        assertEquals("prune retains exactly the kept composite", 1, compositeFiles().size)
        assertTrue("kept composite survives", fileBehind(second).exists())
        assertFalse("other composite pruned", fileBehind(first!!).exists())
        assertFalse("stray temp swept", strayTmp.exists())
    }

    @Test
    fun `delete drops only the named composite`() = runTest {
        // AUDIT-20 F7: a superseded flatten drops its OWN just-written file, leaving
        // every other composite intact.
        val kept = store.write(bitmap(success = true))
        val superseded = store.write(bitmap(success = true))

        store.delete(superseded!!)

        assertTrue("unrelated composite untouched", fileBehind(kept!!).exists())
        assertFalse("superseded composite deleted", fileBehind(superseded).exists())
    }

    @Test
    fun `a failed write keeps the previous composite intact`() = runTest {
        // AUDIT-20 F1/F7: write() never unlinks an existing composite — it only creates
        // a new file (and drops its own temp on failure). A failed write therefore
        // leaves the previous, still-referenced composite untouched.
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

    @Test
    fun `clear removes every composite, not just the latest`() = runTest {
        // AUDIT-20 F8: clear() is the restore/reset path. write() no longer prunes
        // (F7), so several composites can coexist; clear() must sweep all of them —
        // and, being `suspend`, it now runs under the store's dirLock so it cannot
        // interleave the filesystem ops of a concurrent write.
        store.write(bitmap(success = true))
        store.write(bitmap(success = true))
        assertEquals("two composites present before clear", 2, compositeFiles().size)

        store.clear()

        assertTrue("clear sweeps every composite", compositeFiles().isEmpty())
    }
}
