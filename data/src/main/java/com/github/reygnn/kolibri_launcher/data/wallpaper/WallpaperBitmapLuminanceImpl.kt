package com.github.reygnn.kolibri_launcher.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.github.reygnn.kolibri_launcher.core.ColorMath
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperBitmapLuminance
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bitmap at [uri], downscales to a small grid, and returns
 * the median per-pixel WCAG luminance. Median rather than mean to
 * suppress outlier pixels (the sun on a sunset wallpaper, a
 * flashlight glare on a portrait, etc.).
 *
 * Sample size is fixed at [SAMPLE_SIZE]² pixels — small enough that
 * the sort is essentially free, large enough that the median is
 * stable across reasonable wallpapers.
 *
 * Bitmap loading + decode happens on `Dispatchers.IO`. The
 * arithmetic is CPU-bound but tiny (1024 luminance computes + a
 * sort of 1024 floats), so it stays on the same dispatcher rather
 * than ping-ponging.
 *
 * Returns `null` on any failure (file gone, decode failure, OOM,
 * revoked permission). Callers treat `null` as "classification
 * unknown for this URI" — see `ClassifyWallpaperUseCase`.
 */
@Singleton
class WallpaperBitmapLuminanceImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WallpaperBitmapLuminance {

    override suspend fun compute(uri: String): Float? = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(uri) ?: return@withContext null
        try {
            classify(bitmap)
        } finally {
            // Free the decoded bitmap promptly — the scaled copy
            // produced inside `classify` is its own allocation.
            bitmap.recycle()
        }
    }

    private fun loadBitmap(uri: String): Bitmap? {
        // Catch kept per Rule 11: this is the I/O boundary for
        // bitmap loading. Real failure modes are
        // FileNotFoundException + SecurityException (revoked
        // content-URI permission, missing file) and OutOfMemoryError
        // (large bitmap). Throwable umbrella covers OOM intentionally
        // — callers treat null as "classification unknown", which is
        // the right behaviour for any of those cases.
        return try {
            val parsed = Uri.parse(uri)
            context.contentResolver.openInputStream(parsed)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error loading wallpaper bitmap from $uri")
            null
        }
    }

    private fun classify(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        try {
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            val luminances = FloatArray(pixels.size) { i ->
                ColorMath.calculateLuminance(pixels[i]).toFloat()
            }
            luminances.sort()
            return luminances[luminances.size / 2]
        } finally {
            // `createScaledBitmap` may return the input untouched
            // when dimensions match; only recycle a real new copy.
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private companion object {
        /**
         * Per-axis sample count. 32×32 = 1024 pixels — fast to sort,
         * stable median, cheap memory.
         */
        const val SAMPLE_SIZE = 32
    }
}
