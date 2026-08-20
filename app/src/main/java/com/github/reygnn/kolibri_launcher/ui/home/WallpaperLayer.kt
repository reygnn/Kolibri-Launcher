package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a single layer of a multi-layer wallpaper — a "sheet" with its own
 * image and transform.
 *
 * Sheet model:
 * - layers are painted on top of each other (index 0 = bottom-most)
 * - transparent areas (in the image's alpha channel) let lower layers show through
 *
 * The transform matches the existing ZoomableImageView model: Matrix = Scale → Translate.
 */
data class WallpaperLayer(
    /**
     * Unique ID für Persistierung und Identifikation.
     * Wird beim Rebuild aus dem Domain-State übernommen
     * (siehe [WallpaperLayerState.id]), damit View- und Domain-Layer
     * über die gleiche ID matched werden können — und Features wie
     * "aktive Selektion über Rebuild hinweg erhalten" ohne Index-Hack
     * funktionieren.
     */
    val id: String = newId(),

    /** Source URI des Bildes (für Restore nach Process Death) */
    var sourceUri: Uri? = null,

    /** Das geladene Bitmap – null wenn noch nicht geladen */
    var bitmap: Bitmap? = null,

    /** Intrinsische Breite des Originals (vor Scaling beim Laden) */
    var intrinsicWidth: Int = 0,

    /** Intrinsische Höhe des Originals (vor Scaling beim Laden) */
    var intrinsicHeight: Int = 0,

    /**
     * Decode downsample factor of the currently loaded [bitmap] — S_render
     * (WALLPAPER_RENDER_RES_SPEC §4-Y). A save tags the transform with this as
     * `captureSampleSize`; a restore compensates the stored scale by
     * `S_render / S_captured`. `1` = full-res (or unknown / legacy default).
     */
    var sampleSize: Int = 1,

    /**
     * FULL-resolution source dimensions (independent of [sampleSize]/[bitmap]),
     * needed to backfill S_captured for a legacy field-less transform (spec §7).
     * `0` = unknown (older in-memory layer built without decode metadata).
     */
    var originalWidth: Int = 0,
    var originalHeight: Int = 0,

    // --- Transform State ---

    var scale: Float = 1f,
    var translateX: Float = 0f,
    var translateY: Float = 0f,
) {
    companion object {
        // Thread-safe counter. View-seitige Layer werden normalerweise
        // vom Main-Thread erzeugt, aber sicher ist sicher.
        private val counter = AtomicLong(0)

        /**
         * Erzeugt eine neue, prozessweit eindeutige View-Layer-ID.
         * Thread-safe. Wird nur als Fallback verwendet, wenn kein Domain-State
         * eine ID mitliefert.
         */
        fun newId(): String =
            "layer_${System.currentTimeMillis()}_${counter.getAndIncrement()}"
    }

    // ===========================================
    // MATRIX HELPERS
    // ===========================================

    /**
     * Baut die Transformations-Matrix für dieses Layer.
     * Identisch zum Modell in ZoomableImageView: Scale → Translate
     */
    fun buildMatrix(): Matrix {
        return Matrix().apply {
            postScale(scale, scale)
            postTranslate(translateX, translateY)
        }
    }

    /**
     * Allocation-free twin of [buildMatrix] for the per-frame draw path:
     * writes the layer transform into [out] instead of allocating a Matrix.
     */
    fun buildMatrixInto(out: Matrix) {
        out.reset()
        out.postScale(scale, scale)
        out.postTranslate(translateX, translateY)
    }

    /**
     * Berechnet das transformierte Bounding-Rect des Layers.
     * Nützlich für Hit-Testing (welches Layer wurde getappt?).
     */
    fun getTransformedBounds(): RectF? {
        val bmp = bitmap ?: return null
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        buildMatrix().mapRect(rect)
        return rect
    }

    /**
     * Allocation-free twin of [getTransformedBounds] for the per-frame draw
     * path: writes the transformed bounds into [out], using [tmp] as scratch
     * for the matrix. Returns false (leaving [out] untouched) when there is
     * no bitmap.
     */
    fun getTransformedBoundsInto(out: RectF, tmp: Matrix): Boolean {
        val bmp = bitmap ?: return false
        out.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        buildMatrixInto(tmp)
        tmp.mapRect(out)
        return true
    }

    /**
     * Wendet eine "Cover"-Transformation an: Skaliert das Bild so,
     * dass es den angegebenen Bereich komplett ausfüllt, und zentriert es.
     */
    fun applyCenterCrop(viewWidth: Int, viewHeight: Int) {
        val bmp = bitmap ?: return
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()

        scale = maxOf(viewWidth / imgW, viewHeight / imgH)
        translateX = (viewWidth - imgW * scale) / 2f
        translateY = (viewHeight - imgH * scale) / 2f
    }

    /**
     * Skaliert das Bild proportional auf die Breite des Views
     * und zentriert es vertikal. Das Seitenverhältnis bleibt erhalten.
     */
    fun applyFitWidth(viewWidth: Int, viewHeight: Int) {
        val bmp = bitmap ?: return
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()

        scale = viewWidth / imgW
        translateX = 0f
        translateY = (viewHeight - imgH * scale) / 2f
    }
}