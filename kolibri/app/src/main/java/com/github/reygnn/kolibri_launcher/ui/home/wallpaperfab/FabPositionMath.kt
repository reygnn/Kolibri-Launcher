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
     * the parent, excluding any system-UI insets on either edge.
     *
     * When the parent is smaller than the FAB (theoretical edge — happens
     * during the first measure pass), returns `insetStart` rather than a
     * negative offset.
     *
     * [insetStart] / [insetEnd] are the unobstructed-area insets on this
     * axis (status-bar height for `top`, nav-bar height for `bottom`,
     * cutout left/right for the horizontal axis). The fraction itself
     * remains insets-free — that keeps a persisted position portable
     * across devices with different bar heights.
     */
    fun centerFractionToTopLeftPx(
        centerFraction: Float,
        fabSize: Int,
        parentSize: Int,
        insetStart: Int = 0,
        insetEnd: Int = 0,
    ): Int {
        if (parentSize <= fabSize + insetStart + insetEnd) return insetStart
        val center = centerFraction * parentSize
        val topLeft = center - fabSize / 2f
        val maxTopLeft = (parentSize - fabSize - insetEnd).toFloat()
        return topLeft.coerceIn(insetStart.toFloat(), maxTopLeft).toInt()
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
     * [parentSize], with the unobstructed-area insets ([insetStart] /
     * [insetEnd]) carved out on each edge. Used by the drag handler
     * while a drag is in progress so the FAB never lands behind the
     * status-bar / nav-bar / cutout where touches are eaten by the
     * system.
     */
    fun clampTopLeft(
        topLeftPx: Float,
        fabSize: Int,
        parentSize: Int,
        insetStart: Int = 0,
        insetEnd: Int = 0,
    ): Float {
        val maxTopLeft = max(insetStart, parentSize - fabSize - insetEnd).toFloat()
        return topLeftPx.coerceIn(insetStart.toFloat(), maxTopLeft)
    }
}
