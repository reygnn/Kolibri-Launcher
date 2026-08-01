package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [calculateWallpaperInSampleSize]. Pins the invariant that
 * makes the wallpaper crash-fix work: a decoded wallpaper bitmap always ends up
 * below the Canvas ~100 MB per-bitmap draw limit
 * (`RecordingCanvas.throwIfCannotDraw`), which is what crashed
 * `ZoomableImageView.onDraw` on a POCO 108 MP photo.
 */
class BitmapDownsamplingTest {

    private val canvasLimitBytes = 100L * 1024 * 1024 // RecordingCanvas MAX_BITMAP_SIZE

    private fun decodedBytes(w: Int, h: Int, sample: Int): Long =
        (w / sample).toLong() * (h / sample) * 4 // ARGB_8888 = 4 bytes/px

    @Test
    fun `image within bounds is not downsampled`() {
        assertEquals(1, calculateWallpaperInSampleSize(1080, 2400))   // typical screen
        assertEquals(1, calculateWallpaperInSampleSize(4096, 4096))   // exactly the cap
    }

    @Test
    fun `108MP camera photo is downsampled below the Canvas limit`() {
        // The reported crash: 432,000,000 bytes / 4 = 108,000,000 px ~ 12000x9000.
        val src = 12000 to 9000
        val sample = calculateWallpaperInSampleSize(src.first, src.second)

        assertTrue("both sides must be <= cap",
            src.first / sample <= MAX_WALLPAPER_DIMENSION_PX &&
                src.second / sample <= MAX_WALLPAPER_DIMENSION_PX)
        assertTrue("must be under the Canvas draw limit",
            decodedBytes(src.first, src.second, sample) < canvasLimitBytes)
    }

    @Test
    fun `200MP monster is still bounded`() {
        // POCO/Redmi 200 MP ~ 16384x12288.
        val sample = calculateWallpaperInSampleSize(16384, 12288)
        assertTrue(decodedBytes(16384, 12288, sample) < canvasLimitBytes)
        assertTrue(16384 / sample <= MAX_WALLPAPER_DIMENSION_PX)
    }

    @Test
    fun `wide panorama is bounded on the long side`() {
        val sample = calculateWallpaperInSampleSize(20000, 4000)
        assertTrue(20000 / sample <= MAX_WALLPAPER_DIMENSION_PX)
        assertTrue(decodedBytes(20000, 4000, sample) < canvasLimitBytes)
    }

    @Test
    fun `inSampleSize is always a power of two`() {
        for (dim in intArrayOf(5000, 9000, 12000, 16384, 30000)) {
            val s = calculateWallpaperInSampleSize(dim, dim)
            assertEquals("power of two: $s", 0, s and (s - 1))
        }
    }

    @Test
    fun `invalid or unknown dimensions fall back to full decode`() {
        assertEquals(1, calculateWallpaperInSampleSize(-1, -1))   // decodeBounds failed
        assertEquals(1, calculateWallpaperInSampleSize(0, 100))
        assertEquals(1, calculateWallpaperInSampleSize(100, 100, maxDim = 0))
    }
}
