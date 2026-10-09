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
 * (scale, then translate, then the optional output scale), in list order (index 0 = bottom-most).
 * This is the loop of the live view's `drawLayers`; the view-free flatten ([WallpaperFlattener])
 * draws with it, and the dedupe step makes the live view call it too. Until then the two copies
 * must stay identical — the bit-identity is pinned by `WallpaperCompositorParityInstrumentedTest`.
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
        outputScaleX: Float = 1f,
        outputScaleY: Float = 1f,
        afterLayer: (index: Int, layer: WallpaperLayer) -> Unit = { _, _ -> },
    ) {
        if (backgroundColor != Color.TRANSPARENT) {
            canvas.drawColor(backgroundColor)
        }
        val scaled = outputScaleX != 1f || outputScaleY != 1f
        for (index in layers.indices) {
            val layer = layers[index]
            val bmp = layer.bitmap ?: continue
            // Guard: skip a recycled bitmap
            if (bmp.isRecycled) continue

            layer.buildMatrixInto(matrix)
            if (scaled) matrix.postScale(outputScaleX, outputScaleY)
            canvas.drawBitmap(bmp, matrix, paint)

            afterLayer(index, layer)
        }
    }
}
