package com.github.reygnn.launcher.common.ui.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Draws the layers of a multi-layer wallpaper onto a canvas — no View involved
 * (SPEC_NYX_REWRITE Stufe 2).
 *
 * Fills the optional background, then draws every layer that has a live bitmap with its matrix
 * (scale, then translate), in list order (index 0 = bottom-most), at the canvas's own size.
 * The one compositing loop: the live [ZoomableImageView] draws with it on every frame, and the
 * view-free flatten ([WallpaperFlattener]) with its own paint and matrix. That the flatten matches
 * the live view is pinned by `WallpaperCompositorParityInstrumentedTest`.
 *
 * The caller supplies [Paint] and [Matrix], so a per-frame caller can pass its own reused members
 * and an off-Main caller its own local instances. [afterLayer] runs right after each drawn layer
 * (the live view draws its edit-mode selection there); the function is inline, so a per-frame
 * caller allocates no lambda.
 */
object WallpaperLayerPainter {

    /** The paint the live view and the flatten draw layers with (anti-alias + bitmap filtering). */
    fun newLayerPaint(): Paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    inline fun draw(
        canvas: Canvas,
        layers: List<WallpaperLayer>,
        backgroundColor: Int,
        paint: Paint,
        matrix: Matrix,
        afterLayer: (index: Int, layer: WallpaperLayer) -> Unit = { _, _ -> },
    ) {
        if (backgroundColor != Color.TRANSPARENT) {
            canvas.drawColor(backgroundColor)
        }
        for (index in layers.indices) {
            val layer = layers[index]
            val bmp = layer.bitmap ?: continue
            // Guard: skip a recycled bitmap
            if (bmp.isRecycled) continue

            layer.buildMatrixInto(matrix)
            canvas.drawBitmap(bmp, matrix, paint)

            afterLayer(index, layer)
        }
    }
}
