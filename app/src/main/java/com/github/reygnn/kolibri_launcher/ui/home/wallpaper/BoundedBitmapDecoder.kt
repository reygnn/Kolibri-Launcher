package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Bounded wallpaper decode: read the image bounds, then decode with an
 * `inSampleSize` (see [calculateWallpaperInSampleSize]) so the result stays below
 * the Canvas ~100 MB per-bitmap draw limit — the fix for the "Canvas: trying to
 * draw too large(… bytes) bitmap" crash (#21).
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
fun decodeBoundedWallpaperBitmap(openStream: () -> InputStream?): Bitmap? {
    // Bounds pass: decodeStream returns null in inJustDecodeBounds mode (by
    // design — it only fills bounds.outWidth/outHeight), so we must NOT treat that
    // null as failure. Only a null STREAM is a real failure here.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (openStream() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }

    val opts = BitmapFactory.Options().apply {
        inSampleSize = calculateWallpaperInSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }
}
