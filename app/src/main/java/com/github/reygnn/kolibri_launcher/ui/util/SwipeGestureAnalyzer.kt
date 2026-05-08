package com.github.reygnn.kolibri_launcher.ui.util

import kotlin.math.abs

/**
 * PURE LOGIC — swipe-gesture analyzer.
 *
 * Analyzes the raw data of a fling event and decides whether (and which)
 * action should be triggered.
 *
 * Decision basis:
 * 1. Dominant axis (X vs Y)
 * 2. Minimum distance (pixels)
 * 3. Minimum velocity
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

        // 1. Determine the dominant axis.
        if (absDiffX > absDiffY * dominanceFactor) {
            // Horizontal swipe.
            if (absDiffX > distanceThreshold && absVelX > velocityThreshold) {
                return if (diffX > 0) SwipeResult.TOWARDS_RIGHT else SwipeResult.TOWARDS_LEFT
            }
        } else if (absDiffY > absDiffX * dominanceFactor) {
            // Vertical swipe.
            if (absDiffY > distanceThreshold && absVelY > velocityThreshold) {
                return if (diffY < 0) SwipeResult.UP else SwipeResult.DOWN
            }
        }

        // Too short, too slow, or diagonally ambiguous.
        return SwipeResult.IGNORED
    }

    enum class SwipeResult {
        TOWARDS_LEFT, TOWARDS_RIGHT, UP, DOWN, IGNORED
    }
}