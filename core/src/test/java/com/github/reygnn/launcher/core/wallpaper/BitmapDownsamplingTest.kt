package com.github.reygnn.launcher.core.wallpaper

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        assertThat(calculateWallpaperInSampleSize(1080, 2400)).isEqualTo(1)
    }

    @Test
    fun `16MP camera photo is left at full resolution (no transform shift)`() {
        // 4608x3456 = 15.9 MP ~ 64 MB: fits under the Canvas limit, so it must
        // NOT be downsampled — otherwise a saved zoom/pan would jump on update.
        assertThat(calculateWallpaperInSampleSize(4608, 3456)).isEqualTo(1)
    }

    @Test
    fun `24MP photo is left at full resolution`() {
        // 6000x4000 = 24 MP ~ 96 MB: still under the ~100 MB limit → keep full-res.
        assertThat(calculateWallpaperInSampleSize(6000, 4000)).isEqualTo(1)
    }

    @Test
    fun `108MP camera photo is downsampled below the Canvas limit`() {
        // The reported crash: 432,000,000 bytes / 4 = 108,000,000 px ~ 12000x9000.
        val sample = calculateWallpaperInSampleSize(12000, 9000)
        assertThat(sample > 1).isTrue()
        assertThat(decodedPixels(12000, 9000, sample) <= MAX_WALLPAPER_PIXELS).isTrue()
        assertThat(decodedPixels(12000, 9000, sample) * 4 < canvasLimitBytes).isTrue()
    }

    @Test
    fun `200MP monster is still bounded`() {
        // POCO/Redmi 200 MP ~ 16384x12288.
        val sample = calculateWallpaperInSampleSize(16384, 12288)
        assertThat(decodedPixels(16384, 12288, sample) * 4 < canvasLimitBytes).isTrue()
    }

    @Test
    fun `wide panorama is bounded`() {
        val sample = calculateWallpaperInSampleSize(20000, 4000)
        assertThat(decodedPixels(20000, 4000, sample) <= MAX_WALLPAPER_PIXELS).isTrue()
    }

    @Test
    fun `absurd dimensions do not overflow and stay bounded`() {
        // srcW * srcH here overflows Int — the Long arithmetic must hold.
        val sample = calculateWallpaperInSampleSize(50000, 50000)
        assertThat(decodedPixels(50000, 50000, sample) <= MAX_WALLPAPER_PIXELS).isTrue()
    }

    @Test
    fun `inSampleSize is always a power of two`() {
        for (dim in intArrayOf(6000, 9000, 12000, 16384, 50000)) {
            val s = calculateWallpaperInSampleSize(dim, dim)
            assertWithMessage("power of two: $s").that(s and (s - 1)).isEqualTo(0)
        }
    }

    @Test
    fun `invalid or unknown dimensions fall back to full decode`() {
        assertThat(calculateWallpaperInSampleSize(-1, -1)).isEqualTo(1)   // decodeBounds failed
        assertThat(calculateWallpaperInSampleSize(0, 100)).isEqualTo(1)
        assertThat(calculateWallpaperInSampleSize(100, 100, maxPixels = 0)).isEqualTo(1)
    }

    // ── Render budget (WALLPAPER_RENDER_RES_SPEC §5) ─────────────────────────

    @Test
    fun `render budget downsamples a 16MP photo that the crash budget left full-res`() {
        // 4608x3456 = 15.9 MP: full-res under the 24 MP crash budget (S_captured=1),
        // but downsampled under the smaller render budget → this is exactly the
        // resolution-change the compensation (below) must correct.
        assertThat(calculateWallpaperInSampleSize(4608, 3456, MAX_WALLPAPER_PIXELS)).isEqualTo(1)
        assertThat(calculateWallpaperInSampleSize(4608, 3456, RENDER_WALLPAPER_PIXELS) > 1).isTrue()
    }

    @Test
    fun `render budget keeps a screen-sized image full-res`() {
        assertThat(calculateWallpaperInSampleSize(1080, 2424, RENDER_WALLPAPER_PIXELS)).isEqualTo(1)
    }

    // ── per-side texture cap (blank-wallpaper guard for extreme aspect ratios) ─

    @Test
    fun `per-side cap downsamples an extreme-aspect-ratio image the area budget lets through`() {
        // 20000×525 = 10.5 MP → within the area budget at inSampleSize 1, but the
        // 20000 px side exceeds the GPU texture limit → un-uploadable → blank.
        val areaOnly = calculateWallpaperInSampleSize(20000, 525, RENDER_WALLPAPER_PIXELS)
        assertWithMessage("area budget alone leaves the over-wide side at full res").that(areaOnly).isEqualTo(1)

        val clamped = calculateWallpaperInSampleSize(20000, 525, RENDER_WALLPAPER_PIXELS, maxSide = 8192)
        assertWithMessage("per-side cap must downsample").that(clamped > 1).isTrue()
        assertWithMessage("longest decoded side must fit the cap").that(20000 / clamped <= 8192).isTrue()
    }

    @Test
    fun `per-side cap leaves a normal wallpaper untouched`() {
        // Normal side lengths are far below the cap → identical to area-only.
        assertThat(calculateWallpaperInSampleSize(1080, 2424, RENDER_WALLPAPER_PIXELS, maxSide = 8192)).isEqualTo(1)
        assertThat(calculateWallpaperInSampleSize(12000, 9000, RENDER_WALLPAPER_PIXELS, maxSide = 8192)).isEqualTo(calculateWallpaperInSampleSize(12000, 9000, RENDER_WALLPAPER_PIXELS))
    }

    // ── resolveCaptureSampleSize (spec §4-Y / §7 backfill) ───────────────────

    @Test
    fun `stored capture factor is used verbatim when present`() {
        assertThat(resolveCaptureSampleSize(storedFactor = 3, origWidth = 8000, origHeight = 6000)).isEqualTo(3)
    }

    @Test
    fun `legacy field-less transform backfills against the 24MP budget`() {
        // 16 MP: was captured full-res (S=1) under the old budget → backfill = 1.
        assertThat(resolveCaptureSampleSize(null, 4608, 3456)).isEqualTo(1)
        // 24 MP exactly: still full-res under 24M → 1.
        assertThat(resolveCaptureSampleSize(null, 6000, 4000)).isEqualTo(1)
        // 108 MP: crashed pre-#21, downsampled to 4 under the 24M budget → 4.
        assertThat(resolveCaptureSampleSize(null, 12000, 9000)).isEqualTo(calculateWallpaperInSampleSize(12000, 9000, MAX_WALLPAPER_PIXELS))
    }

    // ── compensateScaleForSampleSize (spec §3.2 ratio) ───────────────────────

    @Test
    fun `compensation is identity when the factors match`() {
        assertThat(compensateScaleForSampleSize(2.5f, sCaptured = 2, sRender = 2)).isWithin(1e-6f).of(2.5f)
    }

    @Test
    fun `compensation scales by the render-over-captured ratio`() {
        // Captured full-res (1), now rendered at half (2) → scale must double to
        // keep the image the same on-screen size.
        assertThat(compensateScaleForSampleSize(2.0f, sCaptured = 1, sRender = 2)).isWithin(1e-6f).of(4.0f)
        // Captured at 2 (already-downsampled 108MP), now at 4 → ratio 2.
        assertThat(compensateScaleForSampleSize(3.0f, sCaptured = 2, sRender = 4)).isWithin(1e-6f).of(6.0f)
        // Render smaller factor than captured (budget raised) → scale shrinks.
        assertThat(compensateScaleForSampleSize(2.0f, sCaptured = 4, sRender = 2)).isWithin(1e-6f).of(1.0f)
    }

    @Test
    fun `compensation is a no-op on invalid factors`() {
        assertThat(compensateScaleForSampleSize(2.0f, sCaptured = 0, sRender = 2)).isWithin(1e-6f).of(2.0f)
        assertThat(compensateScaleForSampleSize(2.0f, sCaptured = 2, sRender = 0)).isWithin(1e-6f).of(2.0f)
    }

    @Test
    fun `round-trip capture then render at a new budget is exact for a legacy transform`() {
        // A 16MP image the user zoomed to view_scale=1.3 while it was full-res.
        // Legacy (field-less): S_captured backfills to 1; new render budget → 2.
        val origW = 4608
        val origH = 3456
        val sCaptured = resolveCaptureSampleSize(null, origW, origH) // 1
        val sRender = calculateWallpaperInSampleSize(origW, origH, RENDER_WALLPAPER_PIXELS) // 2
        val compensated = compensateScaleForSampleSize(1.3f, sCaptured, sRender)
        // On the half-resolution bitmap the scale must be doubled to render identically.
        assertThat(compensated).isWithin(1e-6f).of(1.3f * sRender / sCaptured)
    }
}
