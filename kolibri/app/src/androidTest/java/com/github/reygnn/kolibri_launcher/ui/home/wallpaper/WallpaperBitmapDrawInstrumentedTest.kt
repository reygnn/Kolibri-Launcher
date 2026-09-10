package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.graphics.RenderNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the wallpaper "Canvas: trying to draw too large bitmap"
 * crash (#21) and its fix. This EARNS a place in androidTest (CLAUDE.md §10): the
 * two things under test are real-device framework behaviour that Robolectric does
 * not reproduce —
 *  - `RecordingCanvas` (via `RenderNode.beginRecording()`) actually enforces the
 *    ~100 MB per-bitmap draw limit (`throwIfCannotDraw`); Robolectric's shadow
 *    does not, so it can't reproduce the crash; and
 *  - real `BitmapFactory` honours `inSampleSize` while decoding a real JPEG file;
 *    Robolectric doesn't truly decode the asset.
 *
 * The pure size math is covered separately by the JVM `BitmapDownsamplingTest`.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperBitmapDrawInstrumentedTest {

    private val canvasLimitBytes = 100L * 1024 * 1024

    /** Records [block] into a real RecordingCanvas — the exact production draw path. */
    private inline fun onRecordingCanvas(block: (android.graphics.RecordingCanvas) -> Unit) {
        val node = RenderNode("wallpaper-test")
        val canvas = node.beginRecording()
        try {
            block(canvas)
        } finally {
            node.endRecording()
        }
    }

    @Test
    fun realRecordingCanvasRejectsAnOverLimitBitmap() {
        // ~108 MB (5300 x 5100 x 4) — just over the limit, allocatable in native
        // graphics memory. This reproduces the reported crash.
        val over = Bitmap.createBitmap(5300, 5100, Bitmap.Config.ARGB_8888)
        assertTrue(over.byteCount > canvasLimitBytes)
        try {
            val thrown: Throwable? = try {
                onRecordingCanvas { it.drawBitmap(over, 0f, 0f, null) }
                null
            } catch (t: Throwable) {
                t
            }
            // On real hardware (hwui) this throws the reported crash. A
            // software-rendered emulator may not enforce the limit — skip there.
            Assume.assumeTrue(
                "device does not enforce the Canvas per-bitmap draw limit " +
                    "(software-rendered emulator); over-limit repro is device-only",
                thrown != null,
            )
            assertTrue(
                "expected the 'too large' Canvas message, got: ${thrown!!.message}",
                thrown.message?.contains("too large", ignoreCase = true) == true,
            )
        } finally {
            over.recycle()
        }
    }

    @Test
    fun aBoundedBitmapDrawsWithoutError() {
        val bounded = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888) // 16 MB
        assertTrue(bounded.byteCount < canvasLimitBytes)
        try {
            onRecordingCanvas { it.drawBitmap(bounded, 0f, 0f, null) } // must not throw
        } finally {
            bounded.recycle()
        }
    }

    @Test
    fun decodeBoundedWallpaperBitmapDownsamplesAnOversizedAssetAndDrawsSafely() {
        // oversized_wallpaper.jpg is 6000x6000 = 36 MP → 144 MB at full resolution,
        // over both the 24 MP budget and the ~100 MB Canvas limit.
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

        val decoded = decodeBoundedWallpaperBitmap { testAssets.open("oversized_wallpaper.jpg") }

        assertNotNull("asset must decode", decoded)
        val bmp = decoded!!.bitmap
        try {
            assertTrue("must be downsampled from 6000 px", bmp.width < 6000)
            assertTrue(
                "decoded bitmap must be under the Canvas draw limit (${bmp.byteCount} B)",
                bmp.byteCount < canvasLimitBytes,
            )
            // And the real proof: it draws on a RecordingCanvas without throwing.
            onRecordingCanvas { it.drawBitmap(bmp, 0f, 0f, null) }
        } finally {
            bmp.recycle()
        }
    }
}
