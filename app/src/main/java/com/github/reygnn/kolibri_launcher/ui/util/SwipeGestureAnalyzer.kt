package com.github.reygnn.kolibri_launcher.ui.util

import kotlin.math.abs

/**
 * PURE LOGIC - Swipe Gesture Analyzer
 *
 * Analysiert Rohdaten eines Fling-Events und entscheidet,
 * ob und welche Aktion ausgeführt werden soll.
 *
 * Entscheidet basierend auf:
 * 1. Dominante Achse (X vs Y)
 * 2. Mindest-Distanz (Pixel)
 * 3. Mindest-Geschwindigkeit (Velocity)
 *
 * Thresholds and dominance factor are passed as constructor parameters
 * so multiple call sites can share the algorithm with their own
 * calibration. Two consumers in production:
 *
 *  - [com.github.reygnn.kolibri_launcher.ui.home.HomeGestureLayout]
 *  - [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout]
 *
 * Both feed raw deltas-derived velocities (px/ms) from `MotionEvent`
 * and read the same calibration from
 * [com.github.reygnn.kolibri_launcher.ui.util.GestureThresholds]
 * (`scaledTouchSlop * 4`, `1.2f` px/ms, `1.5f` dominance).
 *
 * The analyzer is unit-agnostic: the caller's units must be consistent
 * between input velocities and the `velocityThreshold` parameter.
 */
class SwipeGestureAnalyzer(
    private val distanceThreshold: Float,
    private val velocityThreshold: Float,
    private val dominanceFactor: Float = 1f,
) {

    fun analyze(
        diffX: Float,
        diffY: Float,
        velocityX: Float,
        velocityY: Float
    ): SwipeResult {
        val absDiffX = abs(diffX)
        val absDiffY = abs(diffY)
        val absVelX = abs(velocityX)
        val absVelY = abs(velocityY)

        // 1. Dominante Achse bestimmen
        if (absDiffX > absDiffY * dominanceFactor) {
            // Horizontaler Swipe
            if (absDiffX > distanceThreshold && absVelX > velocityThreshold) {
                return if (diffX > 0) SwipeResult.TOWARDS_RIGHT else SwipeResult.TOWARDS_LEFT
            }
        } else if (absDiffY > absDiffX * dominanceFactor) {
            // Vertikaler Swipe
            if (absDiffY > distanceThreshold && absVelY > velocityThreshold) {
                return if (diffY < 0) SwipeResult.UP else SwipeResult.DOWN
            }
        }

        // Zu kurz, zu langsam oder diagonal ungültig
        return SwipeResult.IGNORED
    }

    enum class SwipeResult {
        TOWARDS_LEFT, TOWARDS_RIGHT, UP, DOWN, IGNORED
    }
}