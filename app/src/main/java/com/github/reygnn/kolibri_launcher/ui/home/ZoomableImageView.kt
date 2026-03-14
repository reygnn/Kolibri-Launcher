package com.github.reygnn.kolibri_launcher.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Custom ImageView mit Pinch-to-Zoom und Pan-Funktionalität.
 *
 * Design-Prinzipien:
 * - Zwei Modi: EDIT (Gesten aktiv) und DISPLAY (Touch-transparent)
 * - Alle Transformationen via Matrix (kein scaleX/Y auf dem View)
 * - Werte jederzeit auslesbar für Persistierung
 * - Crash-safe: Alle Touch-Events in try-catch
 * - Edge Resistance: Progressive Dämpfung wenn Bildkanten sichtbar werden (Rubber-Band)
 * - Snap-Back: Animiert zurück zur Kante beim Loslassen
 *
 * Usage:
 * ```xml
 * <ZoomableImageView
 *     android:id="@+id/wallpaperView"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:scaleType="matrix" />
 * ```
 *
 * ```kotlin
 * wallpaperView.setImageURI(uri)
 * wallpaperView.isEditMode = true  // Aktiviert Gesten
 * wallpaperView.applyTransform(scale, transX, transY)  // Restore from prefs
 * ```
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

        // Drag-Schwellwert: Verhindert versehentliches Verschieben bei Tap
        private const val DRAG_THRESHOLD_PX = 10f

        // Edge Resistance: Progressive Dämpfung (Rubber-Band Effekt)
        // Je kleiner der Wert, desto stärker der Widerstand
        private const val EDGE_RESISTANCE_STRENGTH = 0.015f

        // Snap-Back Animation Dauer
        private const val SNAP_BACK_DURATION_MS = 250L
    }

    // ===========================================
    // STATE
    // ===========================================

    /**
     * Edit-Modus: Wenn true, werden Touch-Events verarbeitet.
     * Wenn false, werden alle Touches durchgereicht (return false).
     */
    var isEditMode = false

    /**
     * Snap-Modi für das Bild-Verhalten beim Loslassen.
     */
    enum class SnapMode {
        /** Snap an Kanten: Bild aligniert zur nächsten Displaykante */
        EDGE,
        /** Snap zur Mitte: Bild wird immer zentriert */
        CENTER
    }

    /**
     * Master-Schalter: Wenn false, kein Snap, kein Rubber-Band, kein Haptic.
     */
    var isSnapEnabled = true

    /**
     * Snap-Modus: EDGE oder CENTER, unabhängig von der Bildgrösse.
     */
    var snapMode = SnapMode.EDGE

    /**
     * Horizontaler Snap: Rubber-Band und Snap-Back auf der X-Achse.
     */
    var isHorizontalSnapEnabled = true

    /**
     * Vertikaler Snap: Rubber-Band und Snap-Back auf der Y-Achse.
     */
    var isVerticalSnapEnabled = true

    /**
     * Callback wenn sich die Transformation ändert.
     * Nützlich für Live-Preview oder "Save"-Button Aktivierung.
     */
    var onTransformChanged: ((scale: Float, translateX: Float, translateY: Float) -> Unit)? = null

    // Aktuelle Transformationswerte (auslesbar für Persistierung)
    var currentScale = DEFAULT_SCALE
        private set
    var currentTranslateX = 0f
        private set
    var currentTranslateY = 0f
        private set

    // ===========================================
    // MATRIX & GESTURE STATE
    // ===========================================

    private val imageMatrix = Matrix()
    private val savedMatrix = Matrix()

    // Für Pan-Geste
    private val startPoint = PointF()
    private var isDragging = false
    private var hasDraggedBeyondThreshold = false

    // Für Pinch-Zoom
    private val scaleDetector: ScaleGestureDetector

    // Matrix-Werte Cache (um Allocations zu vermeiden)
    private val matrixValues = FloatArray(9)

    // Snap-Back Animator (für Cancellation)
    private var snapBackAnimator: ValueAnimator? = null

    // ===========================================
    // EDGE RESISTANCE STATE
    // ===========================================

    // Tracking ob wir bereits an einer Kante waren (für Haptic Feedback)
    private var wasAtLeftEdge = false
    private var wasAtRightEdge = false
    private var wasAtTopEdge = false
    private var wasAtBottomEdge = false

    // Temporäre Speicher für Edge-Resistance (vermeidet Pair-Allocation im Loop)
    private var resDx = 0f
    private var resDy = 0f

    // ===========================================
    // INITIALIZATION
    // ===========================================

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, ScaleListener())

        // Deaktiviert den "Quick Scale" (Doppeltap-and-drag zum Zoomen)
        // Das könnte mit unserem Drag-Handling kollidieren
        scaleDetector.isQuickScaleEnabled = false
    }

    // ===========================================
    // PUBLIC API
    // ===========================================

    /**
     * Wendet eine gespeicherte Transformation an.
     * Aufrufen nach setImageURI/setImageDrawable.
     */
    fun applyTransform(scale: Float, translateX: Float, translateY: Float) {
        try {
            cancelSnapBackAnimation()
            currentScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
            currentTranslateX = translateX
            currentTranslateY = translateY

            rebuildMatrix()
        } catch (e: Exception) {
            // Fallback: Reset auf Default
            resetTransform()
        }
    }

    /**
     * Setzt die Transformation auf Default zurück.
     */
    fun resetTransform() {
        cancelSnapBackAnimation()
        currentScale = DEFAULT_SCALE
        currentTranslateX = 0f
        currentTranslateY = 0f
        rebuildMatrix()
    }

    /**
     * Zentriert das Bild im View mit "Cover"-Verhalten.
     * Das Bild wird so skaliert, dass es den View komplett ausfüllt.
     * Aufrufen nachdem das Drawable gesetzt UND der View gemessen wurde.
     */
    fun centerCrop() {
        val drawable = drawable ?: return
        if (width == 0 || height == 0) return

        try {
            cancelSnapBackAnimation()

            val dWidth = drawable.intrinsicWidth.toFloat()
            val dHeight = drawable.intrinsicHeight.toFloat()
            val vWidth = width.toFloat()
            val vHeight = height.toFloat()

            // Scale to cover (wie centerCrop)
            val scale = max(vWidth / dWidth, vHeight / dHeight)

            // Zentrieren
            val translateX = (vWidth - dWidth * scale) / 2f
            val translateY = (vHeight - dHeight * scale) / 2f

            currentScale = scale
            currentTranslateX = translateX
            currentTranslateY = translateY

            rebuildMatrix()
        } catch (e: Exception) {
            resetTransform()
        }
    }

    // ===========================================
    // TOUCH HANDLING
    // ===========================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Nicht im Edit-Modus? Touch durchreichen.
        if (!isEditMode) return false

        // Kein Bild? Nichts zu transformieren.
        if (drawable == null) return false

        return try {
            handleTouchEvent(event)
        } catch (e: Exception) {
            // Bei jedem Fehler: Geste abbrechen, aber nicht crashen
            isDragging = false
            hasDraggedBeyondThreshold = false
            resetEdgeState()
            false
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        // Scale-Detector bekommt ALLE Events (auch während Drag)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Laufende Snap-Back Animation abbrechen
                cancelSnapBackAnimation()

                savedMatrix.set(imageMatrix)
                startPoint.set(event.x, event.y)
                isDragging = true
                hasDraggedBeyondThreshold = false
                resetEdgeState()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Zweiter Finger: Wir wechseln zu Zoom
                // Speichere aktuelle Matrix für den Zoom-Start
                savedMatrix.set(imageMatrix)
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    // Zoom läuft – Pan ignorieren
                    return true
                }

                if (isDragging && event.pointerCount == 1) {
                    val dx = event.x - startPoint.x
                    val dy = event.y - startPoint.y

                    // Schwellwert-Check
                    if (!hasDraggedBeyondThreshold) {
                        val distance = sqrt(dx * dx + dy * dy)
                        if (distance > DRAG_THRESHOLD_PX) {
                            hasDraggedBeyondThreshold = true
                            // Startpunkt neu setzen, damit der Sprung nicht sichtbar ist
                            startPoint.set(event.x, event.y)
                        }
                    } else {
                        if (isSnapEnabled) {
                            applyEdgeResistance(dx, dy)
                            currentTranslateX += resDx
                            currentTranslateY += resDy
                        } else {
                            currentTranslateX += dx
                            currentTranslateY += dy
                        }
                        rebuildMatrix()

                        // Startpunkt für nächsten Move updaten
                        startPoint.set(event.x, event.y)
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                hasDraggedBeyondThreshold = false
                resetEdgeState()
                extractValuesFromMatrix()

                if (isSnapEnabled) {
                    val snapTarget = calculateSnapBackTarget()
                    if (snapTarget != null) {
                        animateSnapBack(snapTarget.first, snapTarget.second)
                    } else {
                        notifyTransformChanged()
                    }
                } else {
                    notifyTransformChanged()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Ein Finger losgelassen: Matrix-State sichern
                savedMatrix.set(imageMatrix)
                extractValuesFromMatrix()

                // Falls nur noch ein Finger übrig: Startpunkt neu setzen
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount > remainingIndex) {
                    startPoint.set(
                        event.getX(remainingIndex),
                        event.getY(remainingIndex)
                    )
                }
            }
        }

        return true
    }

    // ===========================================
    // EDGE RESISTANCE (RUBBER-BAND)
    // ===========================================

    /**
     * Berechnet gedämpfte Translation und speichert das Ergebnis in [resDx] und [resDy].
     * (Kein Return-Value, um Allocations zu vermeiden)
     *
     * Modus-aware, grössenunabhängig, achsenunabhängig:
     * - Deaktivierte Achsen bekommen dx/dy ungefiltert
     * - EDGE: Rubber-Band wenn Bild über den gültigen Kanten-Bereich hinausgeht
     * - CENTER: Rubber-Band proportional zur Distanz vom Zentrum
     */
    private fun applyEdgeResistance(dx: Float, dy: Float) {
        val drawable = drawable
        if (drawable == null) {
            resDx = dx
            resDy = dy
            return
        }

        val scaledWidth = drawable.intrinsicWidth * currentScale
        val scaledHeight = drawable.intrinsicHeight * currentScale

        // Default: ungefiltert
        resDx = dx
        resDy = dy

        when (snapMode) {
            SnapMode.EDGE -> {
                // === Horizontale Achse ===
                if (isHorizontalSnapEnabled) {
                    val minX = minOf(0f, width - scaledWidth)
                    val maxX = maxOf(0f, width - scaledWidth)

                    val overshootRight = maxOf(0f, currentTranslateX - maxX)
                    val overshootLeft = maxOf(0f, minX - currentTranslateX)

                    when {
                        dx > 0 && (overshootRight > 0 || currentTranslateX + dx > maxX) -> {
                            val overshoot = maxOf(overshootRight, currentTranslateX + dx - maxX)
                            resDx = dx * rubberBandFactor(overshoot)
                            handleEdgeHaptic(isLeft = true)
                        }
                        dx < 0 && (overshootLeft > 0 || currentTranslateX + dx < minX) -> {
                            val overshoot = maxOf(overshootLeft, minX - (currentTranslateX + dx))
                            resDx = dx * rubberBandFactor(overshoot)
                            handleEdgeHaptic(isLeft = false)
                        }
                        else -> {
                            wasAtLeftEdge = false
                            wasAtRightEdge = false
                        }
                    }
                }

                // === Vertikale Achse ===
                if (isVerticalSnapEnabled) {
                    val minY = minOf(0f, height - scaledHeight)
                    val maxY = maxOf(0f, height - scaledHeight)

                    val overshootBottom = maxOf(0f, currentTranslateY - maxY)
                    val overshootTop = maxOf(0f, minY - currentTranslateY)

                    when {
                        dy > 0 && (overshootBottom > 0 || currentTranslateY + dy > maxY) -> {
                            val overshoot = maxOf(overshootBottom, currentTranslateY + dy - maxY)
                            resDy = dy * rubberBandFactor(overshoot)
                            handleEdgeHaptic(isTop = true)
                        }
                        dy < 0 && (overshootTop > 0 || currentTranslateY + dy < minY) -> {
                            val overshoot = maxOf(overshootTop, minY - (currentTranslateY + dy))
                            resDy = dy * rubberBandFactor(overshoot)
                            handleEdgeHaptic(isTop = false)
                        }
                        else -> {
                            wasAtTopEdge = false
                            wasAtBottomEdge = false
                        }
                    }
                }
            }

            SnapMode.CENTER -> {
                val centerX = (width - scaledWidth) / 2f
                val centerY = (height - scaledHeight) / 2f

                if (isHorizontalSnapEnabled) {
                    val distX = Math.abs(currentTranslateX + dx - centerX)
                    resDx = dx * rubberBandFactor(distX)
                }

                if (isVerticalSnapEnabled) {
                    val distY = Math.abs(currentTranslateY + dy - centerY)
                    resDy = dy * rubberBandFactor(distY)
                }
            }
        }
    }

    /**
     * Rubber-Band Faktor: Je grösser der Overshoot, desto kleiner der Faktor.
     * Basiert auf der iOS-typischen logarithmischen Dämpfung.
     *
     * Bei overshoot=0: Faktor=1.0 (keine Dämpfung)
     * Bei overshoot=100px: Faktor≈0.4 (starke Dämpfung)
     */
    private fun rubberBandFactor(overshoot: Float): Float {
        return 1f / (1f + overshoot * EDGE_RESISTANCE_STRENGTH)
    }

    /**
     * Haptisches Feedback beim ERSTEN Erreichen einer Kante.
     * Wird nur einmal pro Kante pro Geste ausgelöst.
     */
    private fun handleEdgeHaptic(isLeft: Boolean? = null, isTop: Boolean? = null) {
        when {
            isLeft == true && !wasAtLeftEdge -> {
                triggerHaptic()
                wasAtLeftEdge = true
            }
            isLeft == false && !wasAtRightEdge -> {
                triggerHaptic()
                wasAtRightEdge = true
            }
            isTop == true && !wasAtTopEdge -> {
                triggerHaptic()
                wasAtTopEdge = true
            }
            isTop == false && !wasAtBottomEdge -> {
                triggerHaptic()
                wasAtBottomEdge = true
            }
        }
    }

    private fun triggerHaptic() {
        performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /**
     * Setzt den Edge-State zurück.
     * Aufrufen bei Gesture-Start und -Ende.
     */
    private fun resetEdgeState() {
        wasAtLeftEdge = false
        wasAtRightEdge = false
        wasAtTopEdge = false
        wasAtBottomEdge = false
    }

    // ===========================================
    // SNAP-BACK ANIMATION
    // ===========================================

    /**
     * Berechnet die korrigierte Position basierend auf [snapMode] und Achsen-Flags.
     * Gibt null zurück wenn keine Korrektur nötig.
     *
     * Deaktivierte Achsen werden nicht korrigiert.
     */
    private fun calculateSnapBackTarget(): Pair<Float, Float>? {
        val drawable = drawable ?: return null

        val scaledWidth = drawable.intrinsicWidth * currentScale
        val scaledHeight = drawable.intrinsicHeight * currentScale

        var targetX = currentTranslateX
        var targetY = currentTranslateY
        var needsSnap = false

        when (snapMode) {
            SnapMode.EDGE -> {
                // === Horizontal ===
                if (isHorizontalSnapEnabled) {
                    val minX = minOf(0f, width - scaledWidth)
                    val maxX = maxOf(0f, width - scaledWidth)

                    when {
                        currentTranslateX < minX -> {
                            targetX = minX
                            needsSnap = true
                        }
                        currentTranslateX > maxX -> {
                            targetX = maxX
                            needsSnap = true
                        }
                        scaledWidth < width -> {
                            val distToLeft = currentTranslateX
                            val distToRight = maxX - currentTranslateX
                            targetX = if (distToLeft <= distToRight) 0f else maxX
                            if (currentTranslateX != targetX) needsSnap = true
                        }
                    }
                }

                // === Vertikal ===
                if (isVerticalSnapEnabled) {
                    val minY = minOf(0f, height - scaledHeight)
                    val maxY = maxOf(0f, height - scaledHeight)

                    when {
                        currentTranslateY < minY -> {
                            targetY = minY
                            needsSnap = true
                        }
                        currentTranslateY > maxY -> {
                            targetY = maxY
                            needsSnap = true
                        }
                        scaledHeight < height -> {
                            val distToTop = currentTranslateY
                            val distToBottom = maxY - currentTranslateY
                            targetY = if (distToTop <= distToBottom) 0f else maxY
                            if (currentTranslateY != targetY) needsSnap = true
                        }
                    }
                }
            }

            SnapMode.CENTER -> {
                if (isHorizontalSnapEnabled) {
                    val centeredX = (width - scaledWidth) / 2f
                    if (currentTranslateX != centeredX) {
                        targetX = centeredX
                        needsSnap = true
                    }
                }

                if (isVerticalSnapEnabled) {
                    val centeredY = (height - scaledHeight) / 2f
                    if (currentTranslateY != centeredY) {
                        targetY = centeredY
                        needsSnap = true
                    }
                }
            }
        }

        return if (needsSnap) targetX to targetY else null
    }

    /**
     * Animiert das Bild zur Zielposition (Snap-Back).
     * Verwendet eine DecelerateInterpolator für natürliches Gefühl.
     */
    private fun animateSnapBack(targetX: Float, targetY: Float) {
        val startX = currentTranslateX
        val startY = currentTranslateY

        snapBackAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_BACK_DURATION_MS
            interpolator = DecelerateInterpolator(2f)

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float

                currentTranslateX = startX + (targetX - startX) * progress
                currentTranslateY = startY + (targetY - startY) * progress

                rebuildMatrix()
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapBackAnimator = null
                    notifyTransformChanged()
                }

                override fun onAnimationCancel(animation: Animator) {
                    snapBackAnimator = null
                }
            })

            start()
        }
    }

    /**
     * Bricht eine laufende Snap-Back Animation ab.
     * Wichtig wenn der User während der Animation erneut toucht.
     */
    private fun cancelSnapBackAnimation() {
        snapBackAnimator?.cancel()
        snapBackAnimator = null
    }

    // ===========================================
    // SCALE GESTURE LISTENER
    // ===========================================

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return try {
                val scaleFactor = detector.scaleFactor

                // Neuen Scale berechnen und begrenzen
                val newScale = currentScale * scaleFactor
                if (newScale < MIN_SCALE || newScale > MAX_SCALE) {
                    return true
                }

                // Inkrementell skalieren um den Fokuspunkt
                imageMatrix.postScale(
                    scaleFactor,
                    scaleFactor,
                    detector.focusX,
                    detector.focusY
                )

                applyMatrix()
                extractValuesFromMatrix()

                true
            } catch (e: Exception) {
                false
            }
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            savedMatrix.set(imageMatrix)

            if (isSnapEnabled) {
                val snapTarget = calculateSnapBackTarget()
                if (snapTarget != null) {
                    animateSnapBack(snapTarget.first, snapTarget.second)
                } else {
                    notifyTransformChanged()
                }
            } else {
                notifyTransformChanged()
            }
        }
    }

    // ===========================================
    // MATRIX HELPERS
    // ===========================================

    private fun rebuildMatrix() {
        imageMatrix.reset()
        imageMatrix.postScale(currentScale, currentScale)
        imageMatrix.postTranslate(currentTranslateX, currentTranslateY)
        applyMatrix()
    }

    private fun applyMatrix() {
        setImageMatrix(imageMatrix)
    }

    private fun extractValuesFromMatrix() {
        imageMatrix.getValues(matrixValues)
        currentScale = matrixValues[Matrix.MSCALE_X]
        currentTranslateX = matrixValues[Matrix.MTRANS_X]
        currentTranslateY = matrixValues[Matrix.MTRANS_Y]
    }

    private fun notifyTransformChanged() {
        onTransformChanged?.invoke(currentScale, currentTranslateX, currentTranslateY)
    }

    // ===========================================
    // VIEW LIFECYCLE
    // ===========================================

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // Bei neuem Bild: Transform beibehalten (User könnte URI wechseln)
        // Falls Reset gewünscht: explizit resetTransform() aufrufen
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // View-Grösse hat sich geändert – Matrix neu anwenden
        // (wichtig bei Rotation)
        rebuildMatrix()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Animation aufräumen um Leaks zu vermeiden
        cancelSnapBackAnimation()
    }
}