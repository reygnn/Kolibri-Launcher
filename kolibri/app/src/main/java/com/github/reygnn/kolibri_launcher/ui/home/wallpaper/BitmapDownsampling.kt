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
 * RENDER budget (WALLPAPER_RENDER_RES_SPEC §5). Smaller than [MAX_WALLPAPER_PIXELS]
 * so the GPU no longer samples a ~6–9× oversized texture per gesture frame (the
 * measured jank cause, spec §1/§2). ≈ 4× a 1080×2424 screen.
 *
 * The two budgets have DISTINCT roles and must not be merged:
 *  - [RENDER_WALLPAPER_PIXELS] — what a bitmap is decoded to NOW (render/jank).
 *  - [MAX_WALLPAPER_PIXELS] — the historical Canvas-crash budget (#21). It is the
 *    reference for the LEGACY backfill only: a field-less (pre-spec) transform was
 *    captured under this budget, so [resolveCaptureSampleSize] reconstructs its
 *    S_captured against it (spec §7). Lowering the render budget must leave this
 *    one untouched, or every legacy transform mis-compensates.
 *
 * TODO(spec §5): the exact value is a zoom-sharpness vs. jank trade-off to
 * confirm by Perfetto re-trace + a visual max-zoom check; kept a constant for now.
 */
const val RENDER_WALLPAPER_PIXELS = 10_500_000

/**
 * Conservative per-side cap (px) for a decoded wallpaper bitmap. An AREA budget
 * alone ([RENDER_WALLPAPER_PIXELS] / [MAX_WALLPAPER_PIXELS]) lets an extreme-
 * aspect-ratio image through at a huge single side — e.g. 20000×525 ≈ 10.5 MP
 * passes at `inSampleSize` 1. Such a side exceeds the GPU max-texture size, and
 * neither a HARDWARE nor an ARGB_8888 bitmap can then be uploaded as a texture
 * ("Bitmap too large to be uploaded into a texture") → a blank wallpaper. 8192 is
 * the minimum `GL_MAX_TEXTURE_SIZE` across the Android-16 device class; normal
 * wallpapers (≤ ~3000 px/side) never approach it, so this only clamps
 * pathological panoramas. Applied by the render decoder
 * ([decodeBoundedWallpaperBitmap]); the legacy transform backfill
 * ([resolveCaptureSampleSize]) deliberately does NOT apply it, so historical
 * `S_captured` values stay unchanged (WALLPAPER_RENDER_RES_SPEC §7).
 */
const val MAX_WALLPAPER_TEXTURE_SIDE = 8192

/**
 * The `inSampleSize` (a power of two, per `BitmapFactory` semantics) that brings
 * a [srcWidth]×[srcHeight] image to at most [maxPixels] decoded pixels AND — when
 * [maxSide] > 0 — at most [maxSide] pixels on each side. Returns 1 for images
 * already within budget or for unknown/invalid dimensions (≤ 0) — the caller then
 * decodes at full size, which is correct for those cases. [maxSide] = 0 (the
 * default) disables the per-side cap; the legacy backfill relies on that.
 */
fun calculateWallpaperInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    maxPixels: Int = MAX_WALLPAPER_PIXELS,
    maxSide: Int = 0,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxPixels <= 0) return 1
    var sample = 1
    // Long arithmetic: srcWidth * srcHeight overflows Int for large images.
    while (true) {
        val w = srcWidth.toLong() / sample
        val h = srcHeight.toLong() / sample
        val overArea = w * h > maxPixels
        val overSide = maxSide > 0 && (w > maxSide || h > maxSide)
        if (!overArea && !overSide) break
        sample *= 2
    }
    return sample
}

/**
 * The decode downsample factor a stored transform should be treated as having been
 * captured at (WALLPAPER_RENDER_RES_SPEC §4-Y / §7).
 *
 * - [storedFactor] non-null → the value persisted with the transform; use it.
 * - [storedFactor] null (LEGACY, field-less) → reconstruct it from the original
 *   image dimensions against the OLD [MAX_WALLPAPER_PIXELS] budget (the budget in
 *   force when field-less transforms were written).
 *
 * Accepted limitation (spec §7): a pre-#21 transform on a 24–26 MP image was
 * captured full-res (S=1) but is reconstructed here as S=2 — the two are
 * indistinguishable in field-less storage. Narrow band; user re-adjusts once.
 */
fun resolveCaptureSampleSize(storedFactor: Int?, origWidth: Int, origHeight: Int): Int =
    storedFactor ?: calculateWallpaperInSampleSize(origWidth, origHeight, MAX_WALLPAPER_PIXELS)

/**
 * Compensates a bitmap-absolute [storedScale] for a change in decode resolution
 * (WALLPAPER_RENDER_RES_SPEC §3.2): a scale captured against a bitmap downsampled
 * by [sCaptured] renders identically against one downsampled by [sRender] iff the
 * scale is multiplied by `sRender / sCaptured`. Translate is view-space and stays
 * unchanged. Returns [storedScale] verbatim when the factors match (the common
 * case: image within both budgets, both factors 1) or on invalid input (≤ 0).
 */
fun compensateScaleForSampleSize(storedScale: Float, sCaptured: Int, sRender: Int): Float {
    if (sCaptured <= 0 || sRender <= 0 || sCaptured == sRender) return storedScale
    return storedScale * (sRender.toFloat() / sCaptured.toFloat())
}
