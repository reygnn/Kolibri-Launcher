package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.core.AppConstants
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
 */
class SwipeGestureAnalyzer {

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
        if (absDiffX > absDiffY) {
            // Horizontaler Swipe
            if (absDiffX > AppConstants.SWIPE_THRESHOLD &&
                absVelX > AppConstants.SWIPE_VELOCITY_THRESHOLD) {
                return if (diffX > 0) SwipeResult.RIGHT else SwipeResult.LEFT
            }
        } else {
            // Vertikaler Swipe
            if (absDiffY > AppConstants.SWIPE_THRESHOLD &&
                absVelY > AppConstants.SWIPE_VELOCITY_THRESHOLD) {
                return if (diffY < 0) SwipeResult.UP else SwipeResult.DOWN
            }
        }

        // Zu kurz, zu langsam oder diagonal ungültig
        return SwipeResult.IGNORED
    }

    enum class SwipeResult {
        LEFT, RIGHT, UP, DOWN, IGNORED
    }
}