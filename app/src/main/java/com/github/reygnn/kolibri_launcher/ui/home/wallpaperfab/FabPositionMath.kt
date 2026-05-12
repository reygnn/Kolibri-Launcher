package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import kotlin.math.max

/**
 * Pure fraction-vs-pixel math for the wallpaper-edit speed-dial FAB.
 *
 * A FAB position is persisted as a pair of fractions in `[0f, 1f]`
 * (see [com.github.reygnn.kolibri_launcher.domain.model.FabPosition]),
 * but laid out and dragged in pixels. This object is the single place
 * that converts between the two and clamps so the FAB never goes
 * off-screen.
 *
 * Everything here is pure — no Android imports — so the math is
 * exercised by [FabPositionMathTest] on the JVM.
 */
internal object FabPositionMath {

    /**
     * Returns the FAB's top-left pixel coordinate given its center as a
     * fraction of [parentSize]. Coordinates are in the parent's local
     * space. The result is clamped so that the FAB stays fully inside
     * the parent.
     *
     * When the parent is smaller than the FAB (theoretical edge — happens
     * during the first measure pass), returns `0` rather than a negative
     * offset.
     */
    fun centerFractionToTopLeftPx(
        centerFraction: Float,
        fabSize: Int,
        parentSize: Int,
    ): Int {
        if (parentSize <= fabSize) return 0
        val center = centerFraction * parentSize
        val topLeft = center - fabSize / 2f
        val maxTopLeft = (parentSize - fabSize).toFloat()
        return topLeft.coerceIn(0f, maxTopLeft).toInt()
    }

    /**
     * Inverse of [centerFractionToTopLeftPx]. Returns the FAB-center
     * position as a fraction of [parentSize] given its current top-left
     * coordinate.
     *
     * Returns `0.5f` when [parentSize] is zero (degenerate; the caller
     * should not be persisting this) so the math object never divides
     * by zero.
     */
    fun topLeftPxToCenterFraction(
        topLeftPx: Float,
        fabSize: Int,
        parentSize: Int,
    ): Float {
        if (parentSize <= 0) return 0.5f
        val center = topLeftPx + fabSize / 2f
        return (center / parentSize).coerceIn(0f, 1f)
    }

    /**
     * Clamps [topLeftPx] so that a FAB of [fabSize] stays fully inside
     * [parentSize]. Used by the drag handler while a drag is in
     * progress.
     */
    fun clampTopLeft(topLeftPx: Float, fabSize: Int, parentSize: Int): Float {
        val maxTopLeft = max(0, parentSize - fabSize).toFloat()
        return topLeftPx.coerceIn(0f, maxTopLeft)
    }
}
