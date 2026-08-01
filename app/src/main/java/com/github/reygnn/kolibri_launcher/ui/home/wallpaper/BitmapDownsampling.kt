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
 * Cap for each side of a decoded wallpaper bitmap. 4096 px keeps the worst case
 * (4096×4096×4 B ≈ 67 MB, ARGB_8888) comfortably under the Canvas ~100 MB draw
 * limit, while staying well above any phone screen resolution (so display + zoom
 * quality is unaffected for realistic images) and within common GL max-texture
 * sizes.
 */
const val MAX_WALLPAPER_DIMENSION_PX = 4096

/**
 * The `inSampleSize` (a power of two, per `BitmapFactory` semantics) that brings
 * a [srcWidth]×[srcHeight] image so both sides are ≤ [maxDim]. Returns 1 for
 * images already within bounds or for unknown/invalid dimensions (≤ 0) — the
 * caller then decodes at full size, which is correct for those cases.
 */
fun calculateWallpaperInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    maxDim: Int = MAX_WALLPAPER_DIMENSION_PX,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxDim <= 0) return 1
    var sample = 1
    while (srcWidth / sample > maxDim || srcHeight / sample > maxDim) {
        sample *= 2
    }
    return sample
}
