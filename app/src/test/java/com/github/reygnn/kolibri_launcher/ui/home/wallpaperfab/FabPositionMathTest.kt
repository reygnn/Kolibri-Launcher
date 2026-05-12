package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import org.junit.Assert.assertEquals
import org.junit.Test

class FabPositionMathTest {

    // ---------- centerFractionToTopLeftPx ----------

    @Test
    fun `centerFractionToTopLeftPx places center at requested fraction`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 0.5f,
            fabSize = 100,
            parentSize = 1000,
        )
        // Center at 500, top-left at 500 - 50 = 450.
        assertEquals(450, topLeft)
    }

    @Test
    fun `centerFractionToTopLeftPx clamps to top-left edge for fraction below visible range`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 0.0f,
            fabSize = 100,
            parentSize = 1000,
        )
        // Center would be 0 → top-left = -50, clamped to 0.
        assertEquals(0, topLeft)
    }

    @Test
    fun `centerFractionToTopLeftPx clamps to bottom-right edge for fraction above visible range`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 1.0f,
            fabSize = 100,
            parentSize = 1000,
        )
        // Center would be 1000 → top-left = 950, max top-left = 900.
        assertEquals(900, topLeft)
    }

    @Test
    fun `centerFractionToTopLeftPx returns 0 when fab larger than parent`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 0.5f,
            fabSize = 200,
            parentSize = 100,
        )
        assertEquals(0, topLeft)
    }

    @Test
    fun `centerFractionToTopLeftPx clamps out-of-range stored values without crashing`() {
        // Repository may have persisted a negative value (drag bug, manual
        // DataStore edit, restore from older app version). Math object
        // must not return a negative top-left.
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = -0.5f,
            fabSize = 100,
            parentSize = 1000,
        )
        assertEquals(0, topLeft)
    }

    // ---------- topLeftPxToCenterFraction ----------

    @Test
    fun `topLeftPxToCenterFraction inverts centerFractionToTopLeftPx`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 0.3f,
            fabSize = 80,
            parentSize = 500,
        )
        val fraction = FabPositionMath.topLeftPxToCenterFraction(
            topLeftPx = topLeft.toFloat(),
            fabSize = 80,
            parentSize = 500,
        )
        assertEquals(0.3f, fraction, 0.01f)
    }

    @Test
    fun `topLeftPxToCenterFraction returns 0_5 for degenerate parent size`() {
        // No division-by-zero, just a safe default.
        val fraction = FabPositionMath.topLeftPxToCenterFraction(
            topLeftPx = 100f,
            fabSize = 50,
            parentSize = 0,
        )
        assertEquals(0.5f, fraction)
    }

    @Test
    fun `topLeftPxToCenterFraction clamps oversized top-left to 1`() {
        val fraction = FabPositionMath.topLeftPxToCenterFraction(
            topLeftPx = 10_000f,
            fabSize = 100,
            parentSize = 1000,
        )
        assertEquals(1f, fraction)
    }

    @Test
    fun `topLeftPxToCenterFraction clamps negative top-left to 0`() {
        val fraction = FabPositionMath.topLeftPxToCenterFraction(
            topLeftPx = -100f,
            fabSize = 100,
            parentSize = 1000,
        )
        assertEquals(0f, fraction)
    }

    // ---------- clampTopLeft ----------

    @Test
    fun `clampTopLeft keeps in-range values untouched`() {
        assertEquals(123f, FabPositionMath.clampTopLeft(123f, fabSize = 50, parentSize = 500))
    }

    @Test
    fun `clampTopLeft clamps below-zero to zero`() {
        assertEquals(0f, FabPositionMath.clampTopLeft(-10f, fabSize = 50, parentSize = 500))
    }

    @Test
    fun `clampTopLeft clamps above-max to max`() {
        assertEquals(
            450f,
            FabPositionMath.clampTopLeft(1000f, fabSize = 50, parentSize = 500),
        )
    }

    @Test
    fun `clampTopLeft yields zero when fab equals parent`() {
        assertEquals(
            0f,
            FabPositionMath.clampTopLeft(100f, fabSize = 500, parentSize = 500),
        )
    }
}
