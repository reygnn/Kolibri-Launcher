package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Result of a bounded wallpaper decode: the (downsampled) bitmap plus the
 * metadata the transform-persistence layer needs (WALLPAPER_RENDER_RES_SPEC §4.0).
 *
 * @property bitmap the decoded, downsampled bitmap
 * @property sampleSize the `inSampleSize` actually used — S_render, the decode
 *     downsample factor of THIS load. Stored on the view layer so a later save
 *     can tag the transform (`captureSampleSize`) and a restore can compensate.
 * @property originalWidth/[originalHeight] the FULL-resolution image dimensions
 *     (from the bounds pass, independent of `inSampleSize`), needed to backfill
 *     `S_captured` for legacy field-less transforms (spec §7).
 */
data class DecodedWallpaperBitmap(
    val bitmap: Bitmap,
    val sampleSize: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

/**
 * Bounded wallpaper decode: read the image bounds, then decode with an
 * `inSampleSize` (see [calculateWallpaperInSampleSize]) so the result stays below
 * both the render budget ([maxPixels], default [RENDER_WALLPAPER_PIXELS] — the
 * jank fix, spec §5) and the Canvas ~100 MB per-bitmap draw limit (#21).
 *
 * The pixel decode targets a **HARDWARE** bitmap: every wallpaper layer is
 * display-only — drawn on the view's hardware-accelerated canvas, never
 * pixel-read (the luminance classifier decodes its own separate bitmap) — so the
 * pixels can live in graphics memory OFF the Java heap. HARDWARE carries alpha,
 * so transparent overlays qualify too. The decode budget bounds BOTH the area
 * ([maxPixels]) and the per-side length ([MAX_WALLPAPER_TEXTURE_SIDE]), so the
 * result always fits a GPU texture — both the HARDWARE decode and the draw
 * succeed even for an extreme-aspect-ratio source (an area-only budget would let
 * an over-wide side through and neither a HARDWARE nor an ARGB_8888 bitmap could
 * upload it). Falls back to [Bitmap.Config.ARGB_8888] only if a HARDWARE decode
 * still returns null — a genuine graphics-memory allocation failure.
 *
 * [openStream] must return a FRESH stream each call: it is invoked for the bounds
 * pass and once per decode attempt (`decodeStream` consumes the stream). Returns
 * `null` if no stream can be opened or the image can't be decoded either way.
 *
 * This is Android-runtime code (real `BitmapFactory`), so it is pinned by an
 * INSTRUMENTED test — Robolectric neither truly decodes a real file nor enforces
 * the Canvas draw limit, so only a real device/emulator exercises it honestly.
 * The pure size math lives in [calculateWallpaperInSampleSize] (JVM-tested).
 */
fun decodeBoundedWallpaperBitmap(
    maxPixels: Int = RENDER_WALLPAPER_PIXELS,
    preferSoftware: Boolean = false,
    openStream: () -> InputStream?,
): DecodedWallpaperBitmap? {
    // Bounds pass: decodeStream returns null in inJustDecodeBounds mode (by
    // design — it only fills bounds.outWidth/outHeight), so we must NOT treat that
    // null as failure. Only a null STREAM is a real failure here.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (openStream() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }

    val sample = calculateWallpaperInSampleSize(
        bounds.outWidth, bounds.outHeight, maxPixels, MAX_WALLPAPER_TEXTURE_SIDE,
    )
    // [preferSoftware] = ARGB_8888 only: the Option-D flatten composes on a
    // software Canvas, which cannot draw HARDWARE bitmaps (WALLPAPER_DRAWER_HOME_
    // REBUILD_SPEC §9.2). The live display path keeps HARDWARE (fallback 8888).
    val bitmap = if (preferSoftware) {
        decodeStreamWith(sample, Bitmap.Config.ARGB_8888, openStream)
    } else {
        decodeStreamWith(sample, Bitmap.Config.HARDWARE, openStream)
            ?: decodeStreamWith(sample, Bitmap.Config.ARGB_8888, openStream)
    } ?: return null
    return DecodedWallpaperBitmap(bitmap, sample, bounds.outWidth, bounds.outHeight)
}

/**
 * One decode attempt at [sample] into [config], from a fresh [openStream]. Returns
 * null if the stream can't be opened or the image can't be decoded into [config].
 */
private fun decodeStreamWith(
    sample: Int,
    config: Bitmap.Config,
    openStream: () -> InputStream?,
): Bitmap? {
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = config
    }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }
}
