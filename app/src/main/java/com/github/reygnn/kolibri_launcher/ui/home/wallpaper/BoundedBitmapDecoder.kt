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
 * [openStream] must return a FRESH stream each call: it is invoked twice, once
 * for the bounds pass and once for the pixel decode. Returns `null` if no stream
 * can be opened or the image can't be decoded.
 *
 * This is Android-runtime code (real `BitmapFactory`), so it is pinned by an
 * INSTRUMENTED test — Robolectric neither truly decodes a real file nor enforces
 * the Canvas draw limit, so only a real device/emulator exercises it honestly.
 * The pure size math lives in [calculateWallpaperInSampleSize] (JVM-tested).
 */
fun decodeBoundedWallpaperBitmap(
    maxPixels: Int = RENDER_WALLPAPER_PIXELS,
    openStream: () -> InputStream?,
): DecodedWallpaperBitmap? {
    // Bounds pass: decodeStream returns null in inJustDecodeBounds mode (by
    // design — it only fills bounds.outWidth/outHeight), so we must NOT treat that
    // null as failure. Only a null STREAM is a real failure here.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (openStream() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }

    val sample = calculateWallpaperInSampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = openStream()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    return DecodedWallpaperBitmap(bitmap, sample, bounds.outWidth, bounds.outHeight)
}
