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
 * the median per-pixel WCAG luminance — but only over **effectively-
 * opaque** pixels, and only if the layer's pixel-level coverage is
 * high enough that it visually dominates the system wallpaper
 * underneath.
 *
 * ## Pixel-level alpha gate
 *
 * `ColorMath.calculateLuminance` operates on RGB only, ignoring
 * the alpha byte. A fully-transparent pixel from a PNG with
 * premultiplied alpha (e.g., the dominant case for AMOLED-to-
 * transparent converted wallpapers, where ~85% of the image is
 * `0x00000000`) would otherwise contribute as solid black to the
 * median, dragging the classification toward DARK even though the
 * pixels are visually invisible.
 *
 * Two thresholds:
 * - [OPAQUE_PIXEL_ALPHA_THRESHOLD]: pixels below this alpha are
 *   considered "effectively transparent" and excluded from the
 *   median. Mirrors the `WallpaperLayerState.alpha`-gate in
 *   `ClassifyWallpaperUseCase` (≥ 0.8 means "opaque enough to
 *   dominate"), one level deeper.
 * - [MIN_OPAQUE_COVERAGE]: if the fraction of effectively-opaque
 *   pixels is below this, the bitmap is treated as not visually
 *   dominant (the system wallpaper shows through too much) and
 *   `compute` returns `null`. The caller then falls through to
 *   the system signal, which is what the user actually perceives.
 *
 * Empirical anchors (testPics/transparent.png, an AMOLED-converted
 * panther illustration over white system wallpaper): 13.8% opaque
 * coverage → falls through → system signal wins. The same image's
 * AMOLED original (testPics/amoled.png) has 100% coverage → passes,
 * classified DARK as expected.
 *
 * ## Sample size & threading
 *
 * Sample size is fixed at [SAMPLE_SIZE]² pixels — small enough that
 * the sort is essentially free, large enough that the median is
 * stable across reasonable wallpapers.
 *
 * Bitmap loading + decode happens on `Dispatchers.IO`. The
 * arithmetic is CPU-bound but tiny (≤1024 luminance computes + a
 * sort of ≤1024 floats), so it stays on the same dispatcher rather
 * than ping-ponging.
 *
 * Returns `null` on any of:
 * - bitmap load/decode failure (file gone, OOM, revoked permission)
 * - pixel-level coverage below [MIN_OPAQUE_COVERAGE]
 *
 * Callers treat `null` uniformly as "classification unknown for
 * this layer, fall through to the system signal" — see
 * `ClassifyWallpaperUseCase`.
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

    private fun classify(bitmap: Bitmap): Float? {
        val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        try {
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)

            // Single pass: count effectively-opaque pixels and collect
            // their luminances. Two-pass would be ~2× slower with no
            // semantic benefit on a 1024-pixel array.
            val luminances = FloatArray(pixels.size)
            var opaqueCount = 0
            for (argb in pixels) {
                val alpha = (argb ushr 24) and 0xFF
                if (alpha >= OPAQUE_PIXEL_ALPHA_THRESHOLD) {
                    luminances[opaqueCount] = ColorMath.calculateLuminance(argb).toFloat()
                    opaqueCount++
                }
            }

            // Pixel-level coverage gate. Below the threshold, the
            // layer is considered visually non-dominant — return null
            // so the caller falls through to the system-wallpaper
            // signal. See KDoc on this class for the empirical
            // anchor (transparent.png at 13.8% coverage).
            val coverage = opaqueCount.toFloat() / pixels.size
            if (coverage < MIN_OPAQUE_COVERAGE) return null

            // Sort only the populated prefix, then take the median.
            // `Arrays.sort(arr, 0, opaqueCount)` would be slightly
            // cheaper than copying, but `copyOf` is plenty fast at
            // ≤1024 floats and keeps the code straightforward.
            val opaqueLuminances = luminances.copyOf(opaqueCount)
            opaqueLuminances.sort()
            return opaqueLuminances[opaqueLuminances.size / 2]
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

        /**
         * Pixel alpha (0..255) below which a pixel is treated as
         * effectively transparent and excluded from the median.
         * 204 ≈ 80% opaque, mirroring the layer-level alpha gate
         * in `ClassifyWallpaperUseCase`.
         */
        const val OPAQUE_PIXEL_ALPHA_THRESHOLD = 204

        /**
         * Minimum fraction of pixels that must clear
         * [OPAQUE_PIXEL_ALPHA_THRESHOLD] for the layer to be treated
         * as visually dominant. Below this, `compute` returns null
         * and the classifier falls through to the system signal.
         *
         * 0.5 (50%) starting value: typical AMOLED-converted
         * wallpapers sit at 10..20% pixel coverage, well below the
         * gate; fully-opaque images sit at ~100%, comfortably above.
         * Tunable upward if a borderline case (e.g., a 60%-coverage
         * dense logo) is reported as misclassified.
         */
        const val MIN_OPAQUE_COVERAGE = 0.5f
    }
}
