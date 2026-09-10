package com.github.reygnn.kolibri_launcher.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperMemoryRow
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Drop-in Replacement für ZoomableImageView mit optionalem Multi-Layer Support.
 *
 * == BACKWARD COMPATIBILITY ==
 * Ohne Aufruf von addLayer() verhält sich dieser View IDENTISCH zum
 * originalen ZoomableImageView:
 * - setImageDrawable / setImageURI / setImageBitmap → setzt Layer 0
 * - applyTransform(scale, tx, ty) → transformiert Layer 0
 * - centerCrop() → Center-Crop auf Layer 0
 * - currentScale / currentTranslateX / currentTranslateY → liest Layer 0
 * - onTransformChanged → Callback mit (scale, tx, ty)
 * - isEditMode, isSnapEnabled, snapMode, etc. → unverändert
 *
 * == MULTI-LAYER MODE (Folien-Modell) ==
 * Sobald addLayer() aufgerufen wird, wechselt der View in den Multi-Layer-Modus:
 * - Mehrere Bitmaps als Folien übereinander (transparente Bereiche = durchsichtig)
 * - Jedes Layer individuell zoom-/pannbar
 * - Tap selektiert Layer, aktives Layer empfängt Gesten
 * - Transparenter Hintergrund möglich → System-Wallpaper scheint durch
 * - composeToBitmap() exportiert alles als ein Wallpaper
 *
 * ```xml
 * <ZoomableImageView
 *     android:id="@+id/wallpaperView"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:scaleType="matrix" />
 * ```
 *
 * Single-Layer (wie bisher):
 * ```kotlin
 * wallpaperView.setImageURI(uri)
 * wallpaperView.isEditMode = true
 * wallpaperView.applyTransform(scale, transX, transY)
 * ```
 *
 * Multi-Layer (Folien):
 * ```kotlin
 * // Transparent → System-Wallpaper scheint durch
 * wallpaperView.layerBackgroundColor = Color.TRANSPARENT
 *
 * wallpaperView.addLayer(bitmapTop)
 * wallpaperView.addLayer(bitmapBottom)
 *
 * wallpaperView.isEditMode = true
 * wallpaperView.activeLayerIndex = 0
 * ```
 *
 * == SIZE NOTE ==
 * This file is ~1,500 lines. A split (e.g. extracting a TouchHandler or
 * MatrixCalculator) has been considered and rejected: the resulting
 * classes would still need View, Matrix, and animator lifecycle, so they
 * wouldn't become JVM-testable. The split would just rearrange the same
 * lines across more files plus extra wiring.
 *
 * If genuine pure-logic islands appear (e.g. a snap-decision predicate
 * that doesn't touch Matrix or animator state), extract them per
 * CLAUDE.md Rule 10. That is the right lever — file-level cuts on
 * View-bound code are mostly cosmetic.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    // ===========================================
    // CONFIGURATION
    // ===========================================

    companion object {
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 5.0f
        private const val DEFAULT_SCALE = 1.0f

        // Multi-Layer erlaubt kleinere Scales (Bilder können Teil des Screens sein)
        private const val MULTI_LAYER_MIN_SCALE = 0.1f
        private const val MULTI_LAYER_MAX_SCALE = 10.0f

        // Relativer Zoom-Faktor bezogen auf den CenterCrop-Scale (Base Scale).
        // Erlaubt Rein-/Rauszoomen unabhängig von der absoluten Bildgröße.
        // z.B. bei einem 50x50 Bild mit baseScale=40: maxScale = 40 * 3 = 120
        private const val ZOOM_IN_MULTIPLIER = 3.0f   // Max 3x über Cover hinaus
        private const val ZOOM_OUT_MULTIPLIER = 0.05f  // Min 5% of the reference scale

        private const val DRAG_THRESHOLD_PX = 10f
        private const val EDGE_RESISTANCE_STRENGTH = 0.01f
        private const val SNAP_BACK_DURATION_MS = 250L

        // Tap Detection
        private const val TAP_MAX_DISTANCE_PX = 15f
        private const val TAP_MAX_DURATION_MS = 300L

        // Selection Highlight
        private const val SELECTION_BORDER_WIDTH = 3f
        private const val SELECTION_BORDER_COLOR = 0x99FFFFFF.toInt()
        private const val SELECTION_CORNER_RADIUS = 4f
    }

    // ===========================================
    // MODE DETECTION
    // ===========================================

    /**
     * True wenn Multi-Layer-Modus aktiv ist (mindestens ein explizites Layer).
     * Im Single-Layer-Modus wird das ImageView-Drawable direkt genutzt.
     */
    val isMultiLayerMode: Boolean
        get() = layers.isNotEmpty()

    // Effektive Scale-Grenzen: Dynamisch basierend auf Base-Scale UND aktuellem Scale.
    // Der Base-Scale ist der CenterCrop-Scale (Bild füllt den Screen).
    // Nach showOriginalSize() kann der aktuelle Scale weit unter dem Base-Scale liegen.
    // Die Grenzen passen sich an: Man kann immer vom aktuellen Scale aus rein- UND rauszoomen.
    private var _singleBaseScale = DEFAULT_SCALE

    private val effectiveMinScale: Float
        get() {
            val currentScale = if (isMultiLayerMode) (activeLayer?.scale ?: 1f) else _singleScale
            val baseScale = if (isMultiLayerMode) {
                activeLayer?.let { computeLayerBaseScale(it) } ?: MULTI_LAYER_MIN_SCALE
            } else {
                _singleBaseScale
            }
            // Referenz = das kleinere von Base und Current
            // → Nach 1:1 (current=1.0, base=40.0): Referenz=1.0, Min=0.05
            // → Nach CenterCrop (current=40.0, base=40.0): Referenz=40.0, Min=2.0
            val referenceScale = minOf(baseScale, currentScale)
            return minOf(
                if (isMultiLayerMode) MULTI_LAYER_MIN_SCALE else MIN_SCALE,
                referenceScale * ZOOM_OUT_MULTIPLIER
            )
        }

    private val effectiveMaxScale: Float
        get() {
            val currentScale = if (isMultiLayerMode) (activeLayer?.scale ?: 1f) else _singleScale
            val baseScale = if (isMultiLayerMode) {
                activeLayer?.let { computeLayerBaseScale(it) } ?: MULTI_LAYER_MAX_SCALE
            } else {
                _singleBaseScale
            }
            // Referenz = das grössere von Base und Current
            // → Erlaubt immer Zoom bis Cover-Grösse UND darüber hinaus
            val referenceScale = maxOf(baseScale, currentScale)
            return maxOf(
                if (isMultiLayerMode) MULTI_LAYER_MAX_SCALE else MAX_SCALE,
                referenceScale * ZOOM_IN_MULTIPLIER
            )
        }

    /**
     * Berechnet den CenterCrop-Scale für ein Layer (= "Base Scale").
     * Das ist der Scale, bei dem das Bild den View exakt ausfüllt.
     */
    private fun computeLayerBaseScale(layer: WallpaperLayer): Float {
        val bmp = layer.bitmap ?: return 1f
        if (width == 0 || height == 0) return 1f
        // Guard the bitmap dims too, mirroring updateSingleBaseScale's dw/dh <= 0
        // check — a zero-dimension bitmap divides to +Infinity and would poison
        // every effectiveMin/MaxScale and CenterCrop matrix derived from it.
        if (bmp.width <= 0 || bmp.height <= 0) return 1f
        return max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
    }

    // ===========================================
    // STATE: SINGLE-LAYER (Original-API)
    // ===========================================

    var isEditMode = false
        set(value) {
            field = value
            if (isMultiLayerMode) invalidate()
        }

    enum class SnapMode { EDGE, CENTER }

    var isSnapEnabled = true
    var snapMode = SnapMode.EDGE
    var isHorizontalSnapEnabled = true
    var isVerticalSnapEnabled = true

    /**
     * Original-Callback: Wird in BEIDEN Modi aufgerufen (für das aktive Layer).
     */
    var onTransformChanged: ((scale: Float, translateX: Float, translateY: Float) -> Unit)? = null

    // Aktuelle Werte – im Multi-Layer-Modus lesen diese vom aktiven Layer
    var currentScale: Float
        get() = if (isMultiLayerMode) (activeLayer?.scale ?: DEFAULT_SCALE) else _singleScale
        private set(value) { _singleScale = value }

    var currentTranslateX: Float
        get() = if (isMultiLayerMode) (activeLayer?.translateX ?: 0f) else _singleTranslateX
        private set(value) { _singleTranslateX = value }

    var currentTranslateY: Float
        get() = if (isMultiLayerMode) (activeLayer?.translateY ?: 0f) else _singleTranslateY
        private set(value) { _singleTranslateY = value }

    // Interne Single-Layer Werte
    private var _singleScale = DEFAULT_SCALE
    private var _singleTranslateX = 0f
    private var _singleTranslateY = 0f

    // Decode metadata for the single-layer bitmap (WALLPAPER_RENDER_RES_SPEC
    // §4-Y). S_render + full-res dims of the current single bitmap; used by the
    // binder to compensate a restored transform and to tag a saved one.
    private var _singleSampleSize = 1
    private var _singleOriginalWidth = 0
    private var _singleOriginalHeight = 0

    /** S_render of the current single-layer bitmap (see [setWallpaperBitmap]). */
    val singleSampleSize: Int get() = _singleSampleSize

    /** Full-resolution width of the current single-layer bitmap (0 = unknown). */
    val singleOriginalWidth: Int get() = _singleOriginalWidth

    /** Full-resolution height of the current single-layer bitmap (0 = unknown). */
    val singleOriginalHeight: Int get() = _singleOriginalHeight

    /**
     * Single-layer twin of [addLayer]'s decode-metadata capture: sets the
     * bitmap via the inherited [setImageBitmap] AND records S_render + the
     * full-resolution dims, so a restored transform can be resolution-
     * compensated and a saved one tagged (spec §4-Y). Callers that route a
     * wallpaper through the bounded decoder MUST use this instead of the plain
     * [setImageBitmap], or the single-layer path loses its S metadata.
     */
    fun setWallpaperBitmap(
        bitmap: Bitmap,
        sampleSize: Int,
        originalWidth: Int,
        originalHeight: Int,
    ) {
        _singleSampleSize = sampleSize
        _singleOriginalWidth = if (originalWidth > 0) originalWidth else bitmap.width * sampleSize
        _singleOriginalHeight = if (originalHeight > 0) originalHeight else bitmap.height * sampleSize
        setImageBitmap(bitmap)
    }

    // ===========================================
    // STATE: MULTI-LAYER
    // ===========================================

    private val layers = mutableListOf<WallpaperLayer>()

    /** Index des aktiven Layers (-1 = keins) */
    var activeLayerIndex: Int = -1
        set(value) {
            field = value.coerceIn(-1, layers.size - 1)
            invalidate()
            onActiveLayerChanged?.invoke(field, activeLayer)
        }

    val activeLayer: WallpaperLayer?
        get() = layers.getOrNull(activeLayerIndex)

    /** Hintergrundfarbe im Multi-Layer-Modus. Default: TRANSPARENT (System-Wallpaper scheint durch) */
    var layerBackgroundColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            if (isMultiLayerMode) invalidate()
        }

    // Multi-Layer Callbacks (optional, zusätzlich zu onTransformChanged)
    var onLayerTransformChanged: ((layerIndex: Int, scale: Float, translateX: Float, translateY: Float) -> Unit)? = null
    var onActiveLayerChanged: ((index: Int, layer: WallpaperLayer?) -> Unit)? = null
    var onLayerTapped: ((index: Int, layer: WallpaperLayer) -> Unit)? = null

    // ===========================================
    // GESTURE STATE (geteilt zwischen beiden Modi)
    // ===========================================

    private val imageMatrix = Matrix()
    private val savedMatrix = Matrix()
    private val startPoint = PointF()
    private var isDragging = false
    private var hasDraggedBeyondThreshold = false

    private val scaleDetector: ScaleGestureDetector
    private val matrixValues = FloatArray(9)
    private var snapBackAnimator: ValueAnimator? = null

    // Tap Detection (Multi-Layer)
    private var tapStartTime = 0L
    private val tapStartPoint = PointF()

    // Edge Resistance
    private var wasAtLeftEdge = false
    private var wasAtRightEdge = false
    private var wasAtTopEdge = false
    private var wasAtBottomEdge = false
    private var resDx = 0f
    private var resDy = 0f

    // ===========================================
    // PAINT OBJECTS (Multi-Layer Rendering)
    // ===========================================

    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = SELECTION_BORDER_WIDTH
        color = SELECTION_BORDER_COLOR
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val drawMatrix = Matrix()
    private val selectionBounds = RectF()
    private val tmpMatrix = Matrix()

    // ===========================================
    // INITIALIZATION
    // ===========================================

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        scaleDetector.isQuickScaleEnabled = false
    }

    // ===========================================
    // PUBLIC API: ORIGINAL (Backward Compatible)
    // ===========================================

    /**
     * Wendet eine gespeicherte Transformation an.
     * Single-Layer: Transformiert das Drawable.
     * Multi-Layer: Transformiert das aktive Layer.
     */
    fun applyTransform(scale: Float, translateX: Float, translateY: Float) {
        cancelSnapBackAnimation()

        if (isMultiLayerMode) {
            val layer = activeLayer ?: return
            // Mirror the single-layer path's corrupt-input guard: coerceIn does
            // NOT reject NaN (NaN.coerceIn(a, b) == NaN), so a non-finite scale
            // would otherwise land in layer.scale unchecked, and a non-finite
            // translate would build a degenerate matrix. Sanitize both.
            val safeScale = if (scale.isFinite() && scale > 0f) scale else DEFAULT_SCALE
            layer.scale = safeScale.coerceIn(effectiveMinScale, effectiveMaxScale)
            layer.translateX = if (translateX.isFinite()) translateX else 0f
            layer.translateY = if (translateY.isFinite()) translateY else 0f
            invalidate()
        } else {
            // Refresh the cover base scale so subsequent gesture bounds
            // (effectiveMin/MaxScale) are correct — it is NOT used to clamp the
            // restore below.
            updateSingleBaseScale()
            // Restore honors the persisted zoom as-is. Do NOT clamp it against
            // effectiveMin/MaxScale: those bounds are derived from the current
            // scale, but the dynamic ceiling that permitted this value when it
            // was created (it grows with the current scale) can't be re-derived
            // here — clamping would silently cap a legitimately persisted high
            // zoom (the #12 bug). The gesture path already bounds interactive
            // input, so the only thing to guard here is corrupt persisted input
            // (non-finite or non-positive) that would otherwise build a
            // degenerate image matrix — scale AND translate, since a non-finite
            // translate feeds postTranslate() just as degenerate a matrix as a
            // bad scale (bad scale -> DEFAULT_SCALE, bad translate -> 0).
            _singleScale = if (scale.isFinite() && scale > 0f) scale else DEFAULT_SCALE
            _singleTranslateX = if (translateX.isFinite()) translateX else 0f
            _singleTranslateY = if (translateY.isFinite()) translateY else 0f
            rebuildSingleMatrix()
        }
    }

    fun resetTransform() {
        cancelSnapBackAnimation()

        if (isMultiLayerMode) {
            val layer = activeLayer ?: return
            layer.scale = DEFAULT_SCALE
            layer.translateX = 0f
            layer.translateY = 0f
            invalidate()
        } else {
            _singleScale = DEFAULT_SCALE
            _singleTranslateX = 0f
            _singleTranslateY = 0f
            rebuildSingleMatrix()
        }
    }

    fun centerCrop() {
        if (isMultiLayerMode) {
            val idx = activeLayerIndex
            if (idx >= 0) centerCropLayer(idx)
            return
        }

        val drawable = drawable ?: return
        if (width == 0 || height == 0) return

        cancelSnapBackAnimation()
        val dWidth = drawable.intrinsicWidth.toFloat()
        val dHeight = drawable.intrinsicHeight.toFloat()
        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        val scale = max(vWidth / dWidth, vHeight / dHeight)

        _singleBaseScale = scale  // Remember the base scale for dynamic zoom bounds.
        _singleScale = scale
        _singleTranslateX = (vWidth - dWidth * scale) / 2f
        _singleTranslateY = (vHeight - dHeight * scale) / 2f
        rebuildSingleMatrix()
    }

    /**
     * Zeigt das Bild in Originalgröße (1:1 Pixel) zentriert im View.
     * Scale = 1.0, Bild wird mittig positioniert.
     * Im Multi-Layer-Modus wirkt es auf das aktive Layer.
     */
    fun showOriginalSize() {
        // "Original size" = 1:1 ORIGINAL pixels. The bitmap is decoded
        // downsampled by S, so scale = S draws it at its original resolution,
        // and the centering term must use the drawn size bmp.width*S — not
        // bmp.width, or the image lands off-centre by bmp.width*(S-1)/2
        // (WALLPAPER_RENDER_RES_SPEC §6.1).
        if (isMultiLayerMode) {
            val layer = activeLayer ?: return
            val bmp = layer.bitmap ?: return
            if (width == 0 || height == 0) return

            cancelSnapBackAnimation()
            val s = layer.sampleSize.toFloat()
            layer.scale = s
            layer.translateX = (width - bmp.width * s) / 2f
            layer.translateY = (height - bmp.height * s) / 2f
            invalidate()
            return
        }

        val drawable = drawable ?: return
        if (width == 0 || height == 0) return

        cancelSnapBackAnimation()
        val s = _singleSampleSize.toFloat()
        _singleScale = s
        _singleTranslateX = (width - drawable.intrinsicWidth * s) / 2f
        _singleTranslateY = (height - drawable.intrinsicHeight * s) / 2f
        rebuildSingleMatrix()
    }

    /**
     * Skaliert das Bild proportional auf die Display-Breite und zentriert vertikal.
     * Im Multi-Layer-Modus wirkt es auf das aktive Layer.
     */
    fun fitToWidth() {
        if (isMultiLayerMode) {
            val idx = activeLayerIndex
            if (idx >= 0) fitToWidthLayer(idx)
            return
        }

        val drawable = drawable ?: return
        if (width == 0 || height == 0) return

        cancelSnapBackAnimation()
        val dWidth = drawable.intrinsicWidth.toFloat()
        val dHeight = drawable.intrinsicHeight.toFloat()

        _singleScale = width / dWidth
        _singleBaseScale = _singleScale
        _singleTranslateX = 0f
        _singleTranslateY = (height - dHeight * _singleScale) / 2f
        rebuildSingleMatrix()
    }

    fun fitToWidthLayer(layerIndex: Int) {
        val layer = layers.getOrNull(layerIndex) ?: return
        if (width == 0 || height == 0) return
        cancelSnapBackAnimation()
        layer.applyFitWidth(width, height)
        invalidate()
    }

    // ===========================================
    // PUBLIC API: MULTI-LAYER
    // ===========================================

    /**
     * Fügt ein Layer (Folie) hinzu und aktiviert den Multi-Layer-Modus.
     * Beim ersten Aufruf: Das native Drawable wird deaktiviert.
     *
     * @param bitmap Das Bild für diese Folie
     * @param label Optionaler Name (z.B. "Oben", "Unten")
     * @param centerCrop When true, the new layer is auto-scaled to fit the view
     *   width on first layout. Legacy name — it applies fit-width, not center-crop.
     * @param sourceUri Source URI für Persistierung
     * @return Index des neuen Layers
     */
    fun addLayer(
        bitmap: Bitmap,
        centerCrop: Boolean = true,
        sourceUri: Uri? = null,
        id: String? = null,
        sampleSize: Int = 1,
        originalWidth: Int = 0,
        originalHeight: Int = 0
    ): Int {
        // Beim ersten Layer: Native Drawable entfernen
        if (layers.isEmpty()) {
            super.setImageDrawable(null)
        }

        val layer = WallpaperLayer(
            id = id ?: WallpaperLayer.newId(),
            sourceUri = sourceUri,
            bitmap = bitmap,
            intrinsicWidth = bitmap.width,
            intrinsicHeight = bitmap.height,
            sampleSize = sampleSize,
            // Fall back to the loaded bitmap dims * sampleSize when the caller
            // supplies no original dims, so the backfill still has a sane input.
            originalWidth = if (originalWidth > 0) originalWidth else bitmap.width * sampleSize,
            originalHeight = if (originalHeight > 0) originalHeight else bitmap.height * sampleSize,
        )

        if (centerCrop && width > 0 && height > 0) {
            layer.applyFitWidth(width, height)
        }

        layers.add(layer)
        val index = layers.size - 1

        if (layers.size == 1) {
            activeLayerIndex = 0
        }

        invalidate()
        return index
    }

    /** Public API – aktuell nicht intern genutzt, aber Teil der View-Schnittstelle. */
    @Suppress("unused")
    fun removeLayer(index: Int): Boolean {
        if (index !in layers.indices) return false
        layers.removeAt(index)

        // Recompute activeLayerIndex consistently. The previous version fell
        // through silently when the active layer was exactly the one being
        // removed — leaving activeLayerIndex dangling on the post-shift slot
        // and skipping the setter (no onActiveLayerChanged, no invalidate via setter).
        activeLayerIndex = when {
            layers.isEmpty() -> -1
            activeLayerIndex == index -> minOf(index, layers.size - 1) // removed active → take slot that shifted in
            activeLayerIndex > index -> activeLayerIndex - 1           // everything above the removed index shifts down
            else -> activeLayerIndex                                    // unaffected
        }

        invalidate()
        return true
    }

    /**
     * Entfernt alle Layer und kehrt zum Single-Layer-Modus zurück.
     */
    fun clearLayers() {
        layers.clear()
        activeLayerIndex = -1
        invalidate()
    }

    fun swapLayers(indexA: Int, indexB: Int): Boolean {
        if (indexA !in layers.indices || indexB !in layers.indices) return false
        val temp = layers[indexA]
        layers[indexA] = layers[indexB]
        layers[indexB] = temp

        when (activeLayerIndex) {
            indexA -> activeLayerIndex = indexB
            indexB -> activeLayerIndex = indexA
        }

        invalidate()
        return true
    }

    fun moveLayerUp(index: Int): Boolean {
        if (index >= layers.size - 1) return false
        return swapLayers(index, index + 1)
    }

    fun moveLayerDown(index: Int): Boolean {
        if (index <= 0) return false
        return swapLayers(index, index - 1)
    }

    /** Public API – aktuell nicht intern genutzt, aber Teil der View-Schnittstelle. */
    @Suppress("unused")
    fun getLayers(): List<WallpaperLayer> = layers.toList()
    val layerCount: Int get() = layers.size
    fun getLayer(index: Int): WallpaperLayer? = layers.getOrNull(index)

    /**
     * Retained-bitmap memory of the currently displayed wallpaper as pure data
     * (Rule 10): one [WallpaperMemoryRow] per multi-layer layer, or a single row
     * for the single-layer drawable. `allocationByteCount` is the true size of the
     * backing pixel allocation (≈ width·height·4 for an ARGB_8888 software bitmap;
     * a HARDWARE bitmap's GPU-side allocation may differ from that due to
     * format/stride). The wallpaper bitmaps are HARDWARE bitmaps
     * (BoundedBitmapDecoder), so this size lives in graphics memory OFF the Java
     * heap — `allocationByteCount` reports it all the same. Recycled / absent
     * bitmaps are skipped. Feeds both the info dialog (`WallpaperEditController`)
     * and the optional diagnostic log below.
     *
     * No try/catch (Rule 11): bitmap property reads on a non-recycled bitmap
     * cannot throw; called from pure Main-thread view code, no suspension point.
     */
    fun collectWallpaperMemoryRows(): List<WallpaperMemoryRow> {
        val rows = ArrayList<WallpaperMemoryRow>()
        if (isMultiLayerMode) {
            for (i in 0 until layerCount) {
                val layer = getLayer(i) ?: continue
                val bmp = layer.bitmap ?: continue
                if (bmp.isRecycled) continue
                rows.add(
                    WallpaperMemoryRow(
                        index = i,
                        decodedWidth = bmp.width,
                        decodedHeight = bmp.height,
                        sampleSize = layer.sampleSize,
                        originalWidth = layer.originalWidth,
                        originalHeight = layer.originalHeight,
                        bytes = bmp.allocationByteCount.toLong(),
                        config = bmp.config?.name ?: "?",
                    )
                )
            }
        } else {
            val d = drawable
            if (d is BitmapDrawable) {
                val bmp = d.bitmap
                if (bmp != null && !bmp.isRecycled) {
                    rows.add(
                        WallpaperMemoryRow(
                            index = 0,
                            decodedWidth = bmp.width,
                            decodedHeight = bmp.height,
                            sampleSize = _singleSampleSize,
                            originalWidth = _singleOriginalWidth,
                            originalHeight = _singleOriginalHeight,
                            bytes = bmp.allocationByteCount.toLong(),
                            config = bmp.config?.name ?: "?",
                        )
                    )
                }
            }
        }
        return rows
    }

    /**
     * TEMPORARY diagnostic (branch chore/wallpaper-mem-logging): logs the total
     * RETAINED wallpaper memory and the per-layer breakdown from
     * [collectWallpaperMemoryRows].
     *
     * Uses `android.util.Log.i` on purpose, NOT Timber: it must be visible in a
     * RELEASE build (no Timber DebugTree is planted there), and it must NOT go
     * through the process-wide AcraTree (a `Timber.w`+ would file a crash report).
     * Read it with `adb logcat -s WallpaperMem`. Call sites in the binder are
     * committed DISABLED — uncomment to re-measure.
     *
     * No try/catch (Rule 11): pure reads + `Log.i` cannot throw; no suspension point.
     */
    fun logRetainedWallpaperMemory(context: String) {
        val rows = collectWallpaperMemoryRows()
        var totalBytes = 0L
        val detail = StringBuilder()
        for (row in rows) {
            totalBytes += row.bytes
            detail.append(
                "\n  layer[${row.index}] ${row.decodedWidth}x${row.decodedHeight} " +
                    "sample=${row.sampleSize} orig=${row.originalWidth}x${row.originalHeight} " +
                    "= ${row.bytes / 1024}KB"
            )
        }
        android.util.Log.i(
            "WallpaperMem",
            "[$context] multiLayer=$isMultiLayerMode layers=$layerCount " +
                "total=${totalBytes / 1024}KB (${"%.1f".format(totalBytes / 1_048_576.0)}MB)$detail"
        )
    }

    // ===========================================
    // PUBLIC API: LAYER PROPERTIES (Folie)
    // ===========================================

    /**
     * Transformation auf ein bestimmtes Layer (per Index).
     */
    fun applyTransform(layerIndex: Int, scale: Float, translateX: Float, translateY: Float) {
        val layer = layers.getOrNull(layerIndex) ?: return
        cancelSnapBackAnimation()

        // Scale-Grenzen basierend auf DIESEM Layer (nicht dem aktiven)
        val baseScale = computeLayerBaseScale(layer)
        val minS = minOf(MULTI_LAYER_MIN_SCALE, baseScale * ZOOM_OUT_MULTIPLIER)
        val maxS = maxOf(MULTI_LAYER_MAX_SCALE, baseScale * ZOOM_IN_MULTIPLIER)

        // Same corrupt-input guard as the active-layer applyTransform: coerceIn
        // passes NaN through, so sanitize scale and translate before they reach
        // the layer (and the image matrix).
        val safeScale = if (scale.isFinite() && scale > 0f) scale else DEFAULT_SCALE
        layer.scale = safeScale.coerceIn(minS, maxS)
        layer.translateX = if (translateX.isFinite()) translateX else 0f
        layer.translateY = if (translateY.isFinite()) translateY else 0f
        invalidate()
    }

    fun centerCropLayer(layerIndex: Int) {
        val layer = layers.getOrNull(layerIndex) ?: return
        if (width == 0 || height == 0) return
        cancelSnapBackAnimation()
        layer.applyCenterCrop(width, height)
        invalidate()
    }

    /** Public API – aktuell nicht intern genutzt, aber Teil der View-Schnittstelle. */
    @Suppress("unused")
    fun centerCropAll() {
        if (width == 0 || height == 0) return
        cancelSnapBackAnimation()
        layers.forEach { it.applyCenterCrop(width, height) }
        invalidate()
    }

    /**
     * Flattens all visible layers into a single bitmap via the shared [drawLayers]
     * compositor — the offscreen flatten for Option D
     * (WALLPAPER_DRAWER_HOME_REBUILD_SPEC §9.2, Approach A). Exercised by
     * `WallpaperFlattenParityInstrumentedTest`, which measured a mean delta of
     * ~0.1 / max 1 (0..255) vs. the hardware render on a Galaxy A36 — i.e. the
     * software compose is faithful, so Approach A is viable.
     *
     * REQUIRES SOFTWARE (`ARGB_8888`) layer bitmaps: the compose targets a
     * software `Canvas`, which cannot draw the HARDWARE bitmaps that
     * `BoundedBitmapDecoder` produces for the live view (it throws "unable to draw
     * hardware bitmaps"). Callers decode/copy layers to a software config first
     * (§9.2/§9.3). Not yet wired into production — Option D Phase 2/3 will;
     * `@Suppress("unused")` stays until then.
     */
    @Suppress("unused")
    fun composeToBitmap(
        targetWidth: Int = width,
        targetHeight: Int = height
    ): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null

        return try {
            val result = createBitmap(targetWidth, targetHeight)
            val canvas = Canvas(result)

            val scaleX = targetWidth.toFloat() / width
            val scaleY = targetHeight.toFloat() / height

            // Export-Paint und -Matrix (eigene Instanzen, Thread-safe).
            // Wichtig: NICHT das Klassen-Member `drawMatrix` benutzen —
            // das wird gleichzeitig von onDraw() verwendet, und composeToBitmap
            // kann von einem Hintergrund-Thread aufgerufen werden.
            val exportPaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            val exportMatrix = Matrix()

            if (isMultiLayerMode) {
                // Shared compositor (single source of truth with onDraw); it fills
                // the background and scales each layer to the target size.
                drawLayers(
                    canvas = canvas,
                    paint = exportPaint,
                    matrix = exportMatrix,
                    outputScaleX = scaleX,
                    outputScaleY = scaleY,
                    drawSelection = false,
                )
            } else {
                // Single-Layer: Drawable rendern
                val d = drawable
                if (d != null) {
                    exportMatrix.reset()
                    exportMatrix.postScale(_singleScale * scaleX, _singleScale * scaleY)
                    exportMatrix.postTranslate(_singleTranslateX * scaleX, _singleTranslateY * scaleY)

                    if (d is BitmapDrawable && d.bitmap != null) {
                        canvas.drawBitmap(d.bitmap, exportMatrix, exportPaint)
                    } else {
                        canvas.withMatrix(exportMatrix) {
                            d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
                            d.draw(this)
                        }
                    }
                }
            }

            result
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): the
            // primary failure mode is OutOfMemoryError on createBitmap()
            // or canvas.drawBitmap() with large dimensions / many layers.
            // OOM extends Error → was missed by the previous Exception
            // catch. Returning null lets the caller (export path) fall
            // back gracefully.
            TimberWrapper.silentError(e, "Error composing wallpaper bitmap")
            null
        }
    }

    // ===========================================
    // DRAWING
    // ===========================================

    override fun onDraw(canvas: Canvas) {
        if (!isMultiLayerMode) {
            // Guard: Prüfen ob das Bitmap noch gültig ist
            val d = drawable
            if (d is BitmapDrawable) {
                val bmp = d.bitmap
                if (bmp == null || bmp.isRecycled) {
                    // Nicht zeichnen — Bitmap ist weg
                    return
                }
            }
            super.onDraw(canvas)
            return
        }

        // Traced (jank): the per-frame multi-layer draw loop. Fires only on
        // invalidate (during gestures / rebuilds, not when idle), so it
        // measures the Main-thread draw-command recording cost of a gesture
        // redraw — distinct from the GPU texture sampling on the RenderThread.
        LaunchTrace.section(LaunchTrace.Names.GESTURE_ONDRAW) {
            drawLayers(
                canvas = canvas,
                paint = bitmapPaint,
                matrix = drawMatrix,
                drawSelection = isEditMode,
            )
        }
    }

    /**
     * Shared multi-layer compositing loop — the single source of truth for both
     * the live [onDraw] path and the offscreen [composeToBitmap] export. Fills the
     * optional background, then draws every visible, non-recycled layer with its
     * alpha, blend mode and transform, optionally scaled to a target size
     * ([outputScaleX]/[outputScaleY] = 1f for the 1:1 live view). [drawSelection]
     * draws the edit-mode highlight on the active layer (live path only).
     *
     * The caller supplies [paint] and [matrix] so the two call sites stay
     * thread-independent: [onDraw] passes the view's members (Main thread only),
     * [composeToBitmap] passes local instances (may run off the Main thread).
     * Keeping ONE loop here is what stops the live and export paths from drifting
     * (the reason `composeToBitmap` used to duplicate this).
     */
    private fun drawLayers(
        canvas: Canvas,
        paint: Paint,
        matrix: Matrix,
        outputScaleX: Float = 1f,
        outputScaleY: Float = 1f,
        drawSelection: Boolean,
    ) {
        if (layerBackgroundColor != Color.TRANSPARENT) {
            canvas.drawColor(layerBackgroundColor)
        }
        val scaled = outputScaleX != 1f || outputScaleY != 1f
        for ((index, layer) in layers.withIndex()) {
            val bmp = layer.bitmap ?: continue
            // Guard: skip a recycled bitmap
            if (bmp.isRecycled) continue

            layer.buildMatrixInto(matrix)
            if (scaled) matrix.postScale(outputScaleX, outputScaleY)
            canvas.drawBitmap(bmp, matrix, paint)

            if (drawSelection && index == activeLayerIndex) {
                drawSelectionHighlight(canvas, layer)
            }
        }
    }

    private fun drawSelectionHighlight(canvas: Canvas, layer: WallpaperLayer) {
        if (!layer.getTransformedBoundsInto(selectionBounds, tmpMatrix)) return
        val inset = SELECTION_BORDER_WIDTH / 2f
        selectionBounds.inset(inset, inset)
        canvas.drawRoundRect(
            selectionBounds,
            SELECTION_CORNER_RADIUS,
            SELECTION_CORNER_RADIUS,
            selectionPaint
        )
    }

    // ===========================================
    // TOUCH HANDLING (Modus-aware)
    // ===========================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) return false
        if (!isMultiLayerMode && drawable == null) return false
        if (isMultiLayerMode && layers.isEmpty()) return false

        return try {
            // Traced (jank): gesture matrix math + invalidate per MotionEvent.
            // Synchronous, no suspension point.
            LaunchTrace.section(LaunchTrace.Names.GESTURE_TOUCH) {
                if (isMultiLayerMode) handleMultiLayerTouch(event)
                else handleSingleLayerTouch(event)
            }
            true
        } catch (e: Throwable) {
            // Catch kept (Unrecoverable / HOME-Activity-resilience boundary,
            // four-category frame): onTouchEvent is invoked by Android's
            // input dispatcher. An unhandled throw here crashes the
            // launcher process. Throwable umbrella also covers OOM during
            // matrix math on extreme values. Reset gesture state so the
            // view doesn't stick in a half-completed drag.
            TimberWrapper.silentError(e, "Error handling wallpaper touch event")
            isDragging = false
            hasDraggedBeyondThreshold = false
            resetEdgeState()
            false
        }
    }

    // --- Single-Layer Touch (Original-Logik, 1:1) ---

    private fun handleSingleLayerTouch(event: MotionEvent) {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnapBackAnimation()
                savedMatrix.set(imageMatrix)
                startPoint.set(event.x, event.y)
                isDragging = true
                hasDraggedBeyondThreshold = false
                resetEdgeState()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                savedMatrix.set(imageMatrix)
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return

                if (isDragging && event.pointerCount == 1) {
                    val dx = event.x - startPoint.x
                    val dy = event.y - startPoint.y

                    if (!hasDraggedBeyondThreshold) {
                        if (sqrt(dx * dx + dy * dy) > DRAG_THRESHOLD_PX) {
                            hasDraggedBeyondThreshold = true
                            startPoint.set(event.x, event.y)
                        }
                    } else {
                        val rawDx = event.x - startPoint.x
                        val rawDy = event.y - startPoint.y

                        if (isSnapEnabled) {
                            applySingleEdgeResistance(rawDx, rawDy)
                            _singleTranslateX += resDx
                            _singleTranslateY += resDy
                        } else {
                            _singleTranslateX += rawDx
                            _singleTranslateY += rawDy
                        }
                        rebuildSingleMatrix()
                        startPoint.set(event.x, event.y)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                hasDraggedBeyondThreshold = false
                resetEdgeState()

                if (isSnapEnabled) {
                    val snap = calculateSingleSnapBack()
                    if (snap != null) animateSingleSnapBack(snap.first, snap.second)
                    else notifySingleTransformChanged()
                } else {
                    notifySingleTransformChanged()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                savedMatrix.set(imageMatrix)
                extractSingleValuesFromMatrix()
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount > remainingIndex) {
                    startPoint.set(event.getX(remainingIndex), event.getY(remainingIndex))
                }
            }
        }
    }

    // --- Multi-Layer Touch ---

    private fun handleMultiLayerTouch(event: MotionEvent) {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnapBackAnimation()
                startPoint.set(event.x, event.y)
                tapStartPoint.set(event.x, event.y)
                tapStartTime = System.currentTimeMillis()
                isDragging = true
                hasDraggedBeyondThreshold = false
                resetEdgeState()
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return

                val layer = activeLayer ?: return

                if (isDragging && event.pointerCount == 1) {
                    val dx = event.x - startPoint.x
                    val dy = event.y - startPoint.y

                    if (!hasDraggedBeyondThreshold) {
                        if (sqrt(dx * dx + dy * dy) > DRAG_THRESHOLD_PX) {
                            hasDraggedBeyondThreshold = true
                            startPoint.set(event.x, event.y)
                        }
                    } else {
                        val rawDx = event.x - startPoint.x
                        val rawDy = event.y - startPoint.y

                        if (isSnapEnabled) {
                            applyLayerEdgeResistance(layer, rawDx, rawDy)
                            layer.translateX += resDx
                            layer.translateY += resDy
                        } else {
                            layer.translateX += rawDx
                            layer.translateY += rawDy
                        }
                        invalidate()
                        startPoint.set(event.x, event.y)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val tapDuration = System.currentTimeMillis() - tapStartTime
                    val tapDist = sqrt(
                        (event.x - tapStartPoint.x).let { it * it } +
                                (event.y - tapStartPoint.y).let { it * it }
                    )
                    if (tapDuration < TAP_MAX_DURATION_MS && tapDist < TAP_MAX_DISTANCE_PX) {
                        handleLayerTap(event.x, event.y)
                    }
                }

                isDragging = false
                hasDraggedBeyondThreshold = false
                resetEdgeState()

                val layer = activeLayer
                if (layer != null && isSnapEnabled) {
                    val snap = calculateLayerSnapBack(layer)
                    if (snap != null) animateLayerSnapBack(layer, snap.first, snap.second)
                    else notifyMultiTransformChanged()
                } else {
                    notifyMultiTransformChanged()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount > remainingIndex) {
                    startPoint.set(event.getX(remainingIndex), event.getY(remainingIndex))
                }
            }
        }
    }

    private fun handleLayerTap(x: Float, y: Float) {
        for (i in layers.indices.reversed()) {
            val layer = layers[i]
            val bounds = layer.getTransformedBounds() ?: continue
            if (bounds.contains(x, y)) {
                if (activeLayerIndex != i) {
                    activeLayerIndex = i
                    triggerHaptic()
                }
                onLayerTapped?.invoke(i, layer)
                return
            }
        }
    }

    // ===========================================
    // SINGLE-LAYER: MATRIX HELPERS
    // ===========================================

    private fun rebuildSingleMatrix() {
        imageMatrix.reset()
        imageMatrix.postScale(_singleScale, _singleScale)
        imageMatrix.postTranslate(_singleTranslateX, _singleTranslateY)
        setImageMatrix(imageMatrix)
    }

    private fun extractSingleValuesFromMatrix() {
        imageMatrix.getValues(matrixValues)
        _singleScale = matrixValues[Matrix.MSCALE_X]
        _singleTranslateX = matrixValues[Matrix.MTRANS_X]
        _singleTranslateY = matrixValues[Matrix.MTRANS_Y]
    }

    /**
     * Berechnet den Base-Scale (CenterCrop-Scale) aus dem aktuellen Drawable.
     * Wird aufgerufen bevor Scale-Grenzen benötigt werden (applyTransform, onSizeChanged).
     */
    private fun updateSingleBaseScale() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        _singleBaseScale = max(width / dw, height / dh)
    }

    private fun notifySingleTransformChanged() {
        onTransformChanged?.invoke(_singleScale, _singleTranslateX, _singleTranslateY)
    }

    private fun notifyMultiTransformChanged() {
        val layer = activeLayer ?: return
        val idx = activeLayerIndex

        // Original-Callback (backward compatible)
        onTransformChanged?.invoke(layer.scale, layer.translateX, layer.translateY)

        // Erweiterter Callback
        onLayerTransformChanged?.invoke(idx, layer.scale, layer.translateX, layer.translateY)
    }

    // ===========================================
    // SINGLE-LAYER: EDGE RESISTANCE
    // ===========================================

    private fun applySingleEdgeResistance(dx: Float, dy: Float) {
        val d = drawable
        if (d == null) { resDx = dx; resDy = dy; return }

        val scaledW = d.intrinsicWidth * _singleScale
        val scaledH = d.intrinsicHeight * _singleScale

        resDx = dx
        resDy = dy

        when (snapMode) {
            SnapMode.EDGE -> {
                if (isHorizontalSnapEnabled) {
                    val minX = minOf(0f, width - scaledW)
                    val maxX = maxOf(0f, width - scaledW)
                    val osR = maxOf(0f, _singleTranslateX - maxX)
                    val osL = maxOf(0f, minX - _singleTranslateX)

                    when {
                        dx > 0 && (osR > 0 || _singleTranslateX + dx > maxX) -> {
                            resDx = dx * rubberBandFactor(maxOf(osR, _singleTranslateX + dx - maxX))
                            handleEdgeHaptic(isLeft = true)
                        }
                        dx < 0 && (osL > 0 || _singleTranslateX + dx < minX) -> {
                            resDx = dx * rubberBandFactor(maxOf(osL, minX - (_singleTranslateX + dx)))
                            handleEdgeHaptic(isLeft = false)
                        }
                        else -> { wasAtLeftEdge = false; wasAtRightEdge = false }
                    }
                }

                if (isVerticalSnapEnabled) {
                    val minY = minOf(0f, height - scaledH)
                    val maxY = maxOf(0f, height - scaledH)
                    val osB = maxOf(0f, _singleTranslateY - maxY)
                    val osT = maxOf(0f, minY - _singleTranslateY)

                    when {
                        dy > 0 && (osB > 0 || _singleTranslateY + dy > maxY) -> {
                            resDy = dy * rubberBandFactor(maxOf(osB, _singleTranslateY + dy - maxY))
                            handleEdgeHaptic(isTop = true)
                        }
                        dy < 0 && (osT > 0 || _singleTranslateY + dy < minY) -> {
                            resDy = dy * rubberBandFactor(maxOf(osT, minY - (_singleTranslateY + dy)))
                            handleEdgeHaptic(isTop = false)
                        }
                        else -> { wasAtTopEdge = false; wasAtBottomEdge = false }
                    }
                }
            }

            SnapMode.CENTER -> {
                val cX = (width - scaledW) / 2f
                val cY = (height - scaledH) / 2f
                if (isHorizontalSnapEnabled) resDx = dx * rubberBandFactor(abs(_singleTranslateX + dx - cX))
                if (isVerticalSnapEnabled) resDy = dy * rubberBandFactor(abs(_singleTranslateY + dy - cY))
            }
        }
    }

    // ===========================================
    // SINGLE-LAYER: SNAP-BACK
    // ===========================================

    private fun calculateSingleSnapBack(): Pair<Float, Float>? {
        val d = drawable ?: return null
        val scaledW = d.intrinsicWidth * _singleScale
        val scaledH = d.intrinsicHeight * _singleScale

        var tX = _singleTranslateX
        var tY = _singleTranslateY
        var needsSnap = false

        when (snapMode) {
            SnapMode.EDGE -> {
                if (isHorizontalSnapEnabled) {
                    val minX = minOf(0f, width - scaledW)
                    val maxX = maxOf(0f, width - scaledW)
                    when {
                        _singleTranslateX < minX -> { tX = minX; needsSnap = true }
                        _singleTranslateX > maxX -> { tX = maxX; needsSnap = true }
                        scaledW < width -> {
                            val dL = _singleTranslateX
                            val dR = maxX - _singleTranslateX
                            tX = if (dL <= dR) 0f else maxX
                            if (_singleTranslateX != tX) needsSnap = true
                        }
                    }
                }
                if (isVerticalSnapEnabled) {
                    val minY = minOf(0f, height - scaledH)
                    val maxY = maxOf(0f, height - scaledH)
                    when {
                        _singleTranslateY < minY -> { tY = minY; needsSnap = true }
                        _singleTranslateY > maxY -> { tY = maxY; needsSnap = true }
                        scaledH < height -> {
                            val dT = _singleTranslateY
                            val dB = maxY - _singleTranslateY
                            tY = if (dT <= dB) 0f else maxY
                            if (_singleTranslateY != tY) needsSnap = true
                        }
                    }
                }
            }
            SnapMode.CENTER -> {
                if (isHorizontalSnapEnabled) {
                    val cX = (width - scaledW) / 2f
                    if (_singleTranslateX != cX) { tX = cX; needsSnap = true }
                }
                if (isVerticalSnapEnabled) {
                    val cY = (height - scaledH) / 2f
                    if (_singleTranslateY != cY) { tY = cY; needsSnap = true }
                }
            }
        }

        return if (needsSnap) tX to tY else null
    }

    private fun animateSingleSnapBack(targetX: Float, targetY: Float) {
        val startX = _singleTranslateX
        val startY = _singleTranslateY

        snapBackAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_BACK_DURATION_MS
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                _singleTranslateX = startX + (targetX - startX) * p
                _singleTranslateY = startY + (targetY - startY) * p
                rebuildSingleMatrix()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapBackAnimator = null
                    notifySingleTransformChanged()
                }
                override fun onAnimationCancel(animation: Animator) {
                    snapBackAnimator = null
                }
            })
            start()
        }
    }

    // ===========================================
    // MULTI-LAYER: EDGE RESISTANCE
    // ===========================================

    private fun applyLayerEdgeResistance(layer: WallpaperLayer, dx: Float, dy: Float) {
        val bmp = layer.bitmap
        if (bmp == null) { resDx = dx; resDy = dy; return }

        val scaledW = bmp.width * layer.scale
        val scaledH = bmp.height * layer.scale

        resDx = dx
        resDy = dy

        when (snapMode) {
            SnapMode.EDGE -> {
                if (isHorizontalSnapEnabled) {
                    val minX = minOf(0f, width - scaledW)
                    val maxX = maxOf(0f, width - scaledW)
                    val osR = maxOf(0f, layer.translateX - maxX)
                    val osL = maxOf(0f, minX - layer.translateX)

                    when {
                        dx > 0 && (osR > 0 || layer.translateX + dx > maxX) -> {
                            resDx = dx * rubberBandFactor(maxOf(osR, layer.translateX + dx - maxX))
                            handleEdgeHaptic(isLeft = true)
                        }
                        dx < 0 && (osL > 0 || layer.translateX + dx < minX) -> {
                            resDx = dx * rubberBandFactor(maxOf(osL, minX - (layer.translateX + dx)))
                            handleEdgeHaptic(isLeft = false)
                        }
                        else -> { wasAtLeftEdge = false; wasAtRightEdge = false }
                    }
                }

                if (isVerticalSnapEnabled) {
                    val minY = minOf(0f, height - scaledH)
                    val maxY = maxOf(0f, height - scaledH)
                    val osB = maxOf(0f, layer.translateY - maxY)
                    val osT = maxOf(0f, minY - layer.translateY)

                    when {
                        dy > 0 && (osB > 0 || layer.translateY + dy > maxY) -> {
                            resDy = dy * rubberBandFactor(maxOf(osB, layer.translateY + dy - maxY))
                            handleEdgeHaptic(isTop = true)
                        }
                        dy < 0 && (osT > 0 || layer.translateY + dy < minY) -> {
                            resDy = dy * rubberBandFactor(maxOf(osT, minY - (layer.translateY + dy)))
                            handleEdgeHaptic(isTop = false)
                        }
                        else -> { wasAtTopEdge = false; wasAtBottomEdge = false }
                    }
                }
            }

            SnapMode.CENTER -> {
                val cX = (width - scaledW) / 2f
                val cY = (height - scaledH) / 2f
                if (isHorizontalSnapEnabled) resDx = dx * rubberBandFactor(abs(layer.translateX + dx - cX))
                if (isVerticalSnapEnabled) resDy = dy * rubberBandFactor(abs(layer.translateY + dy - cY))
            }
        }
    }

    // ===========================================
    // MULTI-LAYER: SNAP-BACK
    // ===========================================

    private fun calculateLayerSnapBack(layer: WallpaperLayer): Pair<Float, Float>? {
        val bmp = layer.bitmap ?: return null
        val scaledW = bmp.width * layer.scale
        val scaledH = bmp.height * layer.scale

        var tX = layer.translateX
        var tY = layer.translateY
        var needsSnap = false

        when (snapMode) {
            SnapMode.EDGE -> {
                if (isHorizontalSnapEnabled) {
                    val minX = minOf(0f, width - scaledW)
                    val maxX = maxOf(0f, width - scaledW)
                    when {
                        layer.translateX < minX -> { tX = minX; needsSnap = true }
                        layer.translateX > maxX -> { tX = maxX; needsSnap = true }
                        scaledW < width -> {
                            val dL = layer.translateX
                            val dR = maxX - layer.translateX
                            tX = if (dL <= dR) 0f else maxX
                            if (layer.translateX != tX) needsSnap = true
                        }
                    }
                }
                if (isVerticalSnapEnabled) {
                    val minY = minOf(0f, height - scaledH)
                    val maxY = maxOf(0f, height - scaledH)
                    when {
                        layer.translateY < minY -> { tY = minY; needsSnap = true }
                        layer.translateY > maxY -> { tY = maxY; needsSnap = true }
                        scaledH < height -> {
                            val dT = layer.translateY
                            val dB = maxY - layer.translateY
                            tY = if (dT <= dB) 0f else maxY
                            if (layer.translateY != tY) needsSnap = true
                        }
                    }
                }
            }
            SnapMode.CENTER -> {
                if (isHorizontalSnapEnabled) {
                    val cX = (width - scaledW) / 2f
                    if (layer.translateX != cX) { tX = cX; needsSnap = true }
                }
                if (isVerticalSnapEnabled) {
                    val cY = (height - scaledH) / 2f
                    if (layer.translateY != cY) { tY = cY; needsSnap = true }
                }
            }
        }

        return if (needsSnap) tX to tY else null
    }

    private fun animateLayerSnapBack(layer: WallpaperLayer, targetX: Float, targetY: Float) {
        val startX = layer.translateX
        val startY = layer.translateY

        snapBackAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_BACK_DURATION_MS
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                layer.translateX = startX + (targetX - startX) * p
                layer.translateY = startY + (targetY - startY) * p
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapBackAnimator = null
                    notifyMultiTransformChanged()
                }
                override fun onAnimationCancel(animation: Animator) {
                    snapBackAnimator = null
                }
            })
            start()
        }
    }

    // ===========================================
    // SHARED: SCALE GESTURE
    // ===========================================

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return if (isMultiLayerMode) activeLayer != null else drawable != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return try {
                val sf = detector.scaleFactor
                val focusX = detector.focusX
                val focusY = detector.focusY

                if (isMultiLayerMode) {
                    val layer = activeLayer ?: return false
                    val newScale = layer.scale * sf
                    if (newScale < effectiveMinScale || newScale > effectiveMaxScale) return true

                    layer.translateX = focusX - (focusX - layer.translateX) * sf
                    layer.translateY = focusY - (focusY - layer.translateY) * sf
                    layer.scale = newScale
                    invalidate()
                } else {
                    val newScale = _singleScale * sf
                    if (newScale < effectiveMinScale || newScale > effectiveMaxScale) return true

                    imageMatrix.postScale(sf, sf, focusX, focusY)
                    setImageMatrix(imageMatrix)
                    extractSingleValuesFromMatrix()
                }

                true
            } catch (e: Throwable) {
                // Catch kept (Expected error, four-category frame):
                // matrix math on extreme zoom values can overflow into
                // ArithmeticException / IllegalStateException, and Matrix
                // mutations on a torn-down view can throw on broken state.
                // Throwable umbrella catches OOM during postScale on huge
                // bitmaps too. Returning false signals "scale not consumed"
                // so the gesture detector recovers.
                TimberWrapper.silentError(e, "Error handling wallpaper scale gesture")
                false
            }
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (isMultiLayerMode) {
                val layer = activeLayer ?: return
                if (isSnapEnabled) {
                    val snap = calculateLayerSnapBack(layer)
                    if (snap != null) animateLayerSnapBack(layer, snap.first, snap.second)
                    else notifyMultiTransformChanged()
                } else {
                    notifyMultiTransformChanged()
                }
            } else {
                savedMatrix.set(imageMatrix)
                if (isSnapEnabled) {
                    val snap = calculateSingleSnapBack()
                    if (snap != null) animateSingleSnapBack(snap.first, snap.second)
                    else notifySingleTransformChanged()
                } else {
                    notifySingleTransformChanged()
                }
            }
        }
    }

    // ===========================================
    // SHARED HELPERS
    // ===========================================

    private fun rubberBandFactor(overshoot: Float): Float {
        return 1f / (1f + overshoot * EDGE_RESISTANCE_STRENGTH)
    }

    private fun handleEdgeHaptic(isLeft: Boolean? = null, isTop: Boolean? = null) {
        when {
            isLeft == true && !wasAtLeftEdge -> { triggerHaptic(); wasAtLeftEdge = true }
            isLeft == false && !wasAtRightEdge -> { triggerHaptic(); wasAtRightEdge = true }
            isTop == true && !wasAtTopEdge -> { triggerHaptic(); wasAtTopEdge = true }
            isTop == false && !wasAtBottomEdge -> { triggerHaptic(); wasAtBottomEdge = true }
        }
    }

    private fun triggerHaptic() {
        performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK
        )
    }

    private fun resetEdgeState() {
        wasAtLeftEdge = false
        wasAtRightEdge = false
        wasAtTopEdge = false
        wasAtBottomEdge = false
    }

    private fun cancelSnapBackAnimation() {
        snapBackAnimator?.cancel()
        snapBackAnimator = null
    }

    // ===========================================
    // VIEW LIFECYCLE
    // ===========================================

    override fun setImageDrawable(drawable: Drawable?) {
        if (isMultiLayerMode) {
            // Im Multi-Layer-Modus: Drawable ignorieren
            // (Layer werden über addLayer() verwaltet)
            return
        }
        super.setImageDrawable(drawable)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Base Scale neu berechnen bei Größenänderung (z.B. Rotation)
        if (!isMultiLayerMode) {
            updateSingleBaseScale()
        }

        if (isSnapEnabled && oldw > 0 && oldh > 0) {
            if (isMultiLayerMode) {
                for (layer in layers) {
                    val snap = calculateLayerSnapBack(layer)
                    if (snap != null) {
                        layer.translateX = snap.first
                        layer.translateY = snap.second
                    }
                }
            } else {
                val snap = calculateSingleSnapBack()
                if (snap != null) {
                    _singleTranslateX = snap.first
                    _singleTranslateY = snap.second
                    notifySingleTransformChanged()
                }
            }
        }

        if (isMultiLayerMode) invalidate()
        else rebuildSingleMatrix()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelSnapBackAnimation()
    }
}