package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong

/**
 * Repräsentiert ein einzelnes Layer im Multi-Layer Wallpaper.
 * Jedes Layer ist eine "Folie" mit eigenem Bild, Transform, Alpha und Blend-Modus.
 *
 * Folien-Modell:
 * - Layer werden übereinander gezeichnet (Index 0 = ganz hinten)
 * - Transparente Bereiche lassen darunterliegende Layer durchscheinen
 * - Alpha steuert die Gesamtdeckkraft der Folie
 * - BlendMode bestimmt wie sich Pixel mit darunterliegenden mischen
 *
 * Die Transformation ist identisch zum bestehenden ZoomableImageView-Modell:
 * Matrix = Scale → Translate
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

    // --- Transform State ---

    var scale: Float = 1f,
    var translateX: Float = 0f,
    var translateY: Float = 0f,

    // --- Display State ---

    /** Sichtbarkeit des Layers */
    var isVisible: Boolean = true,

    /**
     * Deckkraft der Folie: 0.0 (komplett transparent) bis 1.0 (komplett deckend).
     * Wird als Paint.alpha im Rendering angewandt.
     */
    var alpha: Float = 1.0f,

    /**
     * Blend-Modus: Bestimmt wie die Pixel dieses Layers
     * mit den darunterliegenden Layern gemischt werden.
     *
     * null = Standard (SRC_OVER, normales Übereinanderlegen)
     */
    var blendMode: BlendMode? = null,

    /** Optionaler Label für UI (z.B. "Oben", "Unten") */
    var label: String? = null
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

        /**
         * Alle unterstützten Blend-Modi mit lesbaren Labels.
         * Nützlich für eine UI-Auswahl (Spinner/BottomSheet).
         */
        val AVAILABLE_BLEND_MODES: List<Pair<String, BlendMode?>> = listOf(
            "Normal" to null,
            "Multiply" to BlendMode.MULTIPLY,
            "Screen" to BlendMode.SCREEN,
            "Overlay" to BlendMode.OVERLAY,
            "Soft Light" to BlendMode.SOFT_LIGHT,
            "Hard Light" to BlendMode.HARD_LIGHT,
            "Darken" to BlendMode.DARKEN,
            "Lighten" to BlendMode.LIGHTEN,
            "Difference" to BlendMode.DIFFERENCE,
            "Exclusion" to BlendMode.EXCLUSION,
            "Color Dodge" to BlendMode.COLOR_DODGE,
            "Color Burn" to BlendMode.COLOR_BURN
        )
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
     * Berechnet das transformierte Bounding-Rect des Layers.
     * Nützlich für Hit-Testing (welches Layer wurde getappt?).
     */
    fun getTransformedBounds(): RectF? {
        val bmp = bitmap ?: return null
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        buildMatrix().mapRect(rect)
        return rect
    }

    /** Skalierte Breite des Bitmaps. */
    val scaledWidth: Float
        get() = (bitmap?.width ?: intrinsicWidth) * scale

    /** Skalierte Höhe des Bitmaps. */
    val scaledHeight: Float
        get() = (bitmap?.height ?: intrinsicHeight) * scale

    // ===========================================
    // ALPHA HELPERS
    // ===========================================

    /**
     * Setzt Alpha als Int-Wert (0-255).
     * Convenience für Code der mit Int-Alpha arbeitet.
     */
    var alphaInt: Int
        get() = (alpha * 255).toInt().coerceIn(0, 255)
        set(value) { alpha = (value.coerceIn(0, 255) / 255f) }

    // ===========================================
    // PERSISTENCE
    // ===========================================

    /**
     * Exportiert den Transform-State als Map für SharedPreferences / JSON.
     */
    fun toTransformMap(): Map<String, Any> = mapOf(
        "id" to id,
        "sourceUri" to (sourceUri?.toString() ?: ""),
        "scale" to scale,
        "translateX" to translateX,
        "translateY" to translateY,
        "isVisible" to isVisible,
        "alpha" to alpha,
        "blendMode" to (blendMode?.toString() ?: ""),
        "label" to (label ?: "")
    )

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