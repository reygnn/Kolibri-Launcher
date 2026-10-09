package com.github.reygnn.launcher.common.ui.wallpaper

import com.github.reygnn.launcher.core.wallpaper.compensateScaleForSampleSize
import com.github.reygnn.launcher.core.wallpaper.resolveCaptureSampleSize
import kotlin.math.max

/**
 * Where one layer of a multi-layer wallpaper sits in a view of a given size — pure math, no View,
 * no Android types (SPEC_NYX_REWRITE Stufe 2).
 *
 * This is the placement the live multi-layer view applies when it binds a state: a saved
 * transform is compensated for the decode sample size and clamped to the per-layer zoom range; a
 * layer without a saved transform is center-cropped. The view-free flatten ([WallpaperFlattener])
 * places its layers with it. The live [ZoomableImageView] still runs its own copy of this math
 * until the dedupe step makes it call these functions; the bit-identity of the two is pinned by
 * `WallpaperCompositorParityInstrumentedTest`. Keep the operations and their order exactly as they
 * are — float results must match bit for bit.
 */
object WallpaperLayerPlacement {

    /** Absolute floor and ceiling of a layer scale; widened per layer by its base scale. */
    const val MULTI_LAYER_MIN_SCALE = 0.1f
    const val MULTI_LAYER_MAX_SCALE = 10.0f

    /** Zoom range relative to the layer's base (center-crop) scale. */
    const val ZOOM_IN_MULTIPLIER = 3.0f
    const val ZOOM_OUT_MULTIPLIER = 0.05f

    /** Replaces a non-finite or non-positive scale (corrupt input). */
    const val DEFAULT_SCALE = 1.0f

    /** A layer's scale and translate in view pixels (matrix: scale, then translate). */
    data class Placement(val scale: Float, val translateX: Float, val translateY: Float)

    /**
     * The placement of one layer whose decoded bitmap is [bitmapWidth]x[bitmapHeight], decoded
     * with [sampleSize] from a source of [originalWidth]x[originalHeight] (0 = unknown, then the
     * bitmap size times [sampleSize] stands in), in a view of [viewWidth]x[viewHeight].
     * [transform] is the saved transform, or `null` for an untransformed layer (center-crop).
     */
    fun place(
        transform: LayerPropertyUpdate.Transform?,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sampleSize: Int,
        originalWidth: Int,
        originalHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Placement {
        if (transform == null) return centerCrop(bitmapWidth, bitmapHeight, viewWidth, viewHeight)
        val sCaptured = resolveCaptureSampleSize(
            transform.captureSampleSize,
            if (originalWidth > 0) originalWidth else bitmapWidth * sampleSize,
            if (originalHeight > 0) originalHeight else bitmapHeight * sampleSize,
        )
        val scale = compensateScaleForSampleSize(transform.scale, sCaptured, sampleSize)
        return clamped(scale, transform.translateX, transform.translateY, bitmapWidth, bitmapHeight, viewWidth, viewHeight)
    }

    /** Covers the view and centers the image (the default for an untransformed layer). */
    fun centerCrop(bitmapWidth: Int, bitmapHeight: Int, viewWidth: Int, viewHeight: Int): Placement {
        val imgW = bitmapWidth.toFloat()
        val imgH = bitmapHeight.toFloat()
        val scale = maxOf(viewWidth / imgW, viewHeight / imgH)
        return Placement(
            scale = scale,
            translateX = (viewWidth - imgW * scale) / 2f,
            translateY = (viewHeight - imgH * scale) / 2f,
        )
    }

    /**
     * A saved transform, clamped to the layer's zoom range and sanitized: a non-finite or
     * non-positive scale becomes [DEFAULT_SCALE] (then clamped), a non-finite translate becomes 0.
     */
    fun clamped(
        scale: Float,
        translateX: Float,
        translateY: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Placement {
        val base = baseScale(bitmapWidth, bitmapHeight, viewWidth, viewHeight)
        val minS = minOf(MULTI_LAYER_MIN_SCALE, base * ZOOM_OUT_MULTIPLIER)
        val maxS = maxOf(MULTI_LAYER_MAX_SCALE, base * ZOOM_IN_MULTIPLIER)
        val safeScale = if (scale.isFinite() && scale > 0f) scale else DEFAULT_SCALE
        return Placement(
            scale = safeScale.coerceIn(minS, maxS),
            translateX = if (translateX.isFinite()) translateX else 0f,
            translateY = if (translateY.isFinite()) translateY else 0f,
        )
    }

    /**
     * The center-crop scale of a layer, the reference for its zoom range; 1 when the view or the
     * bitmap has no size (a zero dimension would divide to infinity).
     */
    fun baseScale(bitmapWidth: Int, bitmapHeight: Int, viewWidth: Int, viewHeight: Int): Float {
        if (viewWidth == 0 || viewHeight == 0) return 1f
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return 1f
        return max(viewWidth.toFloat() / bitmapWidth, viewHeight.toFloat() / bitmapHeight)
    }
}
