package com.github.reygnn.kolibri_launcher.data.wallpaper

/**
 * Pure downsampling math for the luminance-classification decode (AUDIT-19 F3),
 * kept out of the Android decode path so it is JVM-testable (Rule 10).
 *
 * Mirrors the shape of `:app`'s `calculateWallpaperInSampleSize` but with a much
 * smaller budget: the classifier only samples a `SAMPLE_SIZE`² grid
 * (`WallpaperBitmapLuminanceImpl`), so decoding beyond a few hundred pixels per
 * side is pure waste — a full-resolution decode of a 108 MP wallpaper allocated
 * ~400 MB (OOM risk) only to be scaled down to 32×32. Deliberately NOT shared
 * with the `:app` render-path helper: that lives in a different module (`:app`,
 * not importable from `:data`) and is entangled with the render / legacy-
 * transform budgets. The ~6 lines of power-of-two math are duplicated on purpose.
 */

/**
 * Target decoded pixel area for the luminance sample: 256×256. Comfortably above
 * the 32×32 sample grid (so the downscaled median stays stable) yet far below any
 * real photo, so the decode allocation is bounded to well under a megabyte.
 */
internal const val LUMINANCE_DECODE_MAX_PIXELS = 256 * 256

/**
 * The `inSampleSize` (a power of two, per `BitmapFactory` semantics) that brings a
 * [srcWidth]×[srcHeight] image to at most [maxPixels] decoded pixels. Returns 1
 * for images already within budget or for unknown/invalid dimensions (≤ 0) — the
 * caller then decodes at full size, which is correct for those cases (a
 * bounds-decode that failed reports -1, and a small image needs no downsample).
 */
internal fun luminanceInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    maxPixels: Int = LUMINANCE_DECODE_MAX_PIXELS,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxPixels <= 0) return 1
    var sample = 1
    // Long arithmetic: srcWidth * srcHeight overflows Int for large images.
    while ((srcWidth.toLong() / sample) * (srcHeight.toLong() / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}
