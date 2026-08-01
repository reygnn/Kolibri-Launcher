package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [calculateWallpaperInSampleSize]. Pins two invariants:
 *  1. a decoded wallpaper bitmap always ends up below the Canvas ~100 MB
 *     per-bitmap draw limit (`RecordingCanvas.throwIfCannotDraw`) — the crash on
 *     a POCO 108 MP photo; and
 *  2. an image that already fit under that limit is NOT downsampled, so a saved
 *     zoom/pan transform (stored bitmap-absolute in ZoomableImageView) does not
 *     shift on update.
 */
class BitmapDownsamplingTest {

    private val canvasLimitBytes = 100L * 1024 * 1024 // RecordingCanvas MAX_BITMAP_SIZE

    private fun decodedPixels(w: Int, h: Int, sample: Int): Long =
        (w.toLong() / sample) * (h.toLong() / sample)

    @Test
    fun `typical screen image is not downsampled`() {
        assertEquals(1, calculateWallpaperInSampleSize(1080, 2400))
    }

    @Test
    fun `16MP camera photo is left at full resolution (no transform shift)`() {
        // 4608x3456 = 15.9 MP ~ 64 MB: fits under the Canvas limit, so it must
        // NOT be downsampled — otherwise a saved zoom/pan would jump on update.
        assertEquals(1, calculateWallpaperInSampleSize(4608, 3456))
    }

    @Test
    fun `24MP photo is left at full resolution`() {
        // 6000x4000 = 24 MP ~ 96 MB: still under the ~100 MB limit → keep full-res.
        assertEquals(1, calculateWallpaperInSampleSize(6000, 4000))
    }

    @Test
    fun `108MP camera photo is downsampled below the Canvas limit`() {
        // The reported crash: 432,000,000 bytes / 4 = 108,000,000 px ~ 12000x9000.
        val sample = calculateWallpaperInSampleSize(12000, 9000)
        assertTrue(sample > 1)
        assertTrue(decodedPixels(12000, 9000, sample) <= MAX_WALLPAPER_PIXELS)
        assertTrue(decodedPixels(12000, 9000, sample) * 4 < canvasLimitBytes)
    }

    @Test
    fun `200MP monster is still bounded`() {
        // POCO/Redmi 200 MP ~ 16384x12288.
        val sample = calculateWallpaperInSampleSize(16384, 12288)
        assertTrue(decodedPixels(16384, 12288, sample) * 4 < canvasLimitBytes)
    }

    @Test
    fun `wide panorama is bounded`() {
        val sample = calculateWallpaperInSampleSize(20000, 4000)
        assertTrue(decodedPixels(20000, 4000, sample) <= MAX_WALLPAPER_PIXELS)
    }

    @Test
    fun `absurd dimensions do not overflow and stay bounded`() {
        // srcW * srcH here overflows Int — the Long arithmetic must hold.
        val sample = calculateWallpaperInSampleSize(50000, 50000)
        assertTrue(decodedPixels(50000, 50000, sample) <= MAX_WALLPAPER_PIXELS)
    }

    @Test
    fun `inSampleSize is always a power of two`() {
        for (dim in intArrayOf(6000, 9000, 12000, 16384, 50000)) {
            val s = calculateWallpaperInSampleSize(dim, dim)
            assertEquals("power of two: $s", 0, s and (s - 1))
        }
    }

    @Test
    fun `invalid or unknown dimensions fall back to full decode`() {
        assertEquals(1, calculateWallpaperInSampleSize(-1, -1))   // decodeBounds failed
        assertEquals(1, calculateWallpaperInSampleSize(0, 100))
        assertEquals(1, calculateWallpaperInSampleSize(100, 100, maxPixels = 0))
    }
}
