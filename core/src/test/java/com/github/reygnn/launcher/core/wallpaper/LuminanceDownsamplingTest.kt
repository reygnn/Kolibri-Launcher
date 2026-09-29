package com.github.reygnn.launcher.core.wallpaper

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Pure JVM tests for [luminanceInSampleSize] (AUDIT-19 F3) — no Robolectric,
 * it's Int math.
 */
class LuminanceDownsamplingTest {

    private fun assertPowerOfTwo(value: Int) =
        assertWithMessage("$value is not a power of two").that(value > 0 && (value and (value - 1)) == 0).isTrue()

    private fun assertWithinBudget(w: Int, h: Int, sample: Int) =
        assertWithMessage("($w/$sample)*($h/$sample) exceeds budget").that((w.toLong() / sample) * (h.toLong() / sample) <= LUMINANCE_DECODE_MAX_PIXELS).isTrue()

    @Test
    fun `image already within budget returns 1`() {
        assertThat(luminanceInSampleSize(64, 64)).isEqualTo(1)
        assertThat(luminanceInSampleSize(256, 256)).isEqualTo(1) // exactly the budget
    }

    @Test
    fun `invalid or unknown dimensions return 1 — caller decodes full size`() {
        // BitmapFactory reports -1 for outWidth/outHeight when the bounds
        // decode fails; the impl must then decode at full size, not divide by 0.
        assertThat(luminanceInSampleSize(-1, -1)).isEqualTo(1)
        assertThat(luminanceInSampleSize(0, 1000)).isEqualTo(1)
        assertThat(luminanceInSampleSize(1000, 0)).isEqualTo(1)
    }

    @Test
    fun `oversized image is downsampled to a power of two within budget`() {
        val w = 4000
        val h = 3000
        val sample = luminanceInSampleSize(w, h)
        assertPowerOfTwo(sample)
        assertWithinBudget(w, h, sample)
        // and one step less must still be over budget (minimal downsample)
        assertWithMessage("sample $sample is not minimal").that((w.toLong() / (sample / 2)) * (h.toLong() / (sample / 2)) > LUMINANCE_DECODE_MAX_PIXELS).isTrue()
    }

    @Test
    fun `108 MP wallpaper collapses to a small decode, no Int overflow`() {
        val w = 12_000
        val h = 9_000 // 108 MP — the AUDIT-19 F3 worst case
        val sample = luminanceInSampleSize(w, h)
        assertPowerOfTwo(sample)
        assertWithinBudget(w, h, sample)
        assertWithMessage("expected a real downsample, got $sample").that(sample >= 32).isTrue()
    }

    @Test
    fun `explicit tiny budget still terminates and bounds the area`() {
        val sample = luminanceInSampleSize(1024, 1024, maxPixels = 32 * 32)
        assertPowerOfTwo(sample)
        assertThat((1024L / sample) * (1024L / sample) <= 32 * 32).isTrue()
    }
}
