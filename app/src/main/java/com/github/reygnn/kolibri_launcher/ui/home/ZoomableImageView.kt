package com.github.reygnn.kolibri_launcher.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Custom ImageView mit Pinch-to-Zoom und Pan-Funktionalität.
 *
 * Design-Prinzipien:
 * - Zwei Modi: EDIT (Gesten aktiv) und DISPLAY (Touch-transparent)
 * - Alle Transformationen via Matrix (kein scaleX/Y auf dem View)
 * - Werte jederzeit auslesbar für Persistierung
 * - Crash-safe: Alle Touch-Events in try-catch
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
            false
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        // Scale-Detector bekommt ALLE Events (auch während Drag)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(imageMatrix)
                startPoint.set(event.x, event.y)
                isDragging = true
                hasDraggedBeyondThreshold = false
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
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (distance > DRAG_THRESHOLD_PX) {
                            hasDraggedBeyondThreshold = true
                            // Startpunkt neu setzen, damit der Sprung nicht sichtbar ist
                            startPoint.set(event.x, event.y)
                        }
                    } else {
                        // Tatsächliches Verschieben
                        imageMatrix.set(savedMatrix)
                        imageMatrix.postTranslate(dx, dy)
                        applyMatrix()
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                hasDraggedBeyondThreshold = false
                extractValuesFromMatrix()
                notifyTransformChanged()
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
    // SCALE GESTURE LISTENER
    // ===========================================

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true  // Keine savedMatrix mehr hier
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return try {
                val scaleFactor = detector.scaleFactor

                // Neuen Scale berechnen und begrenzen
                val newScale = currentScale * scaleFactor
                if (newScale < MIN_SCALE || newScale > MAX_SCALE) {
                    return true
                }

                // Inkrementell skalieren (NICHT auf savedMatrix zurücksetzen!)
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
            notifyTransformChanged()
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
}