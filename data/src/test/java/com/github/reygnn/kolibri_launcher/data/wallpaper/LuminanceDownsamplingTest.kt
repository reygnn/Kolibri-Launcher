package com.github.reygnn.kolibri_launcher.data.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [luminanceInSampleSize] (AUDIT-19 F3) — no Robolectric,
 * it's Int math.
 */
class LuminanceDownsamplingTest {

    private fun assertPowerOfTwo(value: Int) =
        assertTrue("$value is not a power of two", value > 0 && (value and (value - 1)) == 0)

    private fun assertWithinBudget(w: Int, h: Int, sample: Int) =
        assertTrue(
            "($w/$sample)*($h/$sample) exceeds budget",
            (w.toLong() / sample) * (h.toLong() / sample) <= LUMINANCE_DECODE_MAX_PIXELS,
        )

    @Test
    fun `image already within budget returns 1`() {
        assertEquals(1, luminanceInSampleSize(64, 64))
        assertEquals(1, luminanceInSampleSize(256, 256)) // exactly the budget
    }

    @Test
    fun `invalid or unknown dimensions return 1 — caller decodes full size`() {
        // BitmapFactory reports -1 for outWidth/outHeight when the bounds
        // decode fails; the impl must then decode at full size, not divide by 0.
        assertEquals(1, luminanceInSampleSize(-1, -1))
        assertEquals(1, luminanceInSampleSize(0, 1000))
        assertEquals(1, luminanceInSampleSize(1000, 0))
    }

    @Test
    fun `oversized image is downsampled to a power of two within budget`() {
        val w = 4000
        val h = 3000
        val sample = luminanceInSampleSize(w, h)
        assertPowerOfTwo(sample)
        assertWithinBudget(w, h, sample)
        // and one step less must still be over budget (minimal downsample)
        assertTrue(
            "sample $sample is not minimal",
            (w.toLong() / (sample / 2)) * (h.toLong() / (sample / 2)) > LUMINANCE_DECODE_MAX_PIXELS,
        )
    }

    @Test
    fun `108 MP wallpaper collapses to a small decode, no Int overflow`() {
        val w = 12_000
        val h = 9_000 // 108 MP — the AUDIT-19 F3 worst case
        val sample = luminanceInSampleSize(w, h)
        assertPowerOfTwo(sample)
        assertWithinBudget(w, h, sample)
        assertTrue("expected a real downsample, got $sample", sample >= 32)
    }

    @Test
    fun `explicit tiny budget still terminates and bounds the area`() {
        val sample = luminanceInSampleSize(1024, 1024, maxPixels = 32 * 32)
        assertPowerOfTwo(sample)
        assertTrue((1024L / sample) * (1024L / sample) <= 32 * 32)
    }
}
