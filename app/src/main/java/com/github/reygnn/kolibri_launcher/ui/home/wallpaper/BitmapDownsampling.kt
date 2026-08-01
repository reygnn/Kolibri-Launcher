package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

/**
 * Pure downsampling math for wallpaper bitmap loading — kept out of the
 * Android-runtime decode path so it is JVM-testable (Rule 10).
 *
 * Background: a user can pick a huge camera photo as a wallpaper (POCO phones
 * ship 108/200 MP cameras). Decoding it at full resolution and drawing it
 * overruns the Canvas ~100 MB per-bitmap draw limit
 * (`RecordingCanvas.throwIfCannotDraw`) → `RuntimeException: Canvas: trying to
 * draw too large(… bytes) bitmap` → crash in `ZoomableImageView.onDraw`.
 * Downsampling the decode via `BitmapFactory.Options.inSampleSize` bounds every
 * wallpaper bitmap safely below that limit.
 */

/**
 * Budget for a decoded wallpaper bitmap, expressed as a PIXEL AREA (ARGB_8888 =
 * 4 bytes/px). 24 M px ≈ 91.5 MB stays under the Canvas ~100 MB per-bitmap draw
 * limit (`RecordingCanvas` MAX_BITMAP_SIZE) with margin.
 *
 * Deliberately an **area** budget, not a per-side cap: only images that would
 * actually overrun the Canvas limit get downsampled. A merely-large photo (e.g.
 * a 16 MP camera image at 4608×3456) already displayed fine, so it is left at
 * full resolution — which matters because [ZoomableImageView] stores single-image
 * zoom/pan as a bitmap-absolute `_singleScale`; downsampling such an image would
 * shift a saved transform. Images big enough to hit this budget were crashing
 * before the fix, so they have no valid saved transform to preserve. The default
 * center-crop path recomputes from the intrinsic size and is transparent to
 * downsampling either way.
 */
const val MAX_WALLPAPER_PIXELS = 24_000_000

/**
 * The `inSampleSize` (a power of two, per `BitmapFactory` semantics) that brings
 * a [srcWidth]×[srcHeight] image to at most [maxPixels] decoded pixels. Returns 1
 * for images already within budget or for unknown/invalid dimensions (≤ 0) — the
 * caller then decodes at full size, which is correct for those cases.
 */
fun calculateWallpaperInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    maxPixels: Int = MAX_WALLPAPER_PIXELS,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxPixels <= 0) return 1
    var sample = 1
    // Long arithmetic: srcWidth * srcHeight overflows Int for large images.
    while ((srcWidth.toLong() / sample) * (srcHeight.toLong() / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}
