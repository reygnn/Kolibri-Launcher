package com.github.reygnn.launcher.common.ui.wallpaperfab

import com.google.common.truth.Truth.assertThat
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
        assertThat(topLeft).isEqualTo(450)
    }

    @Test
    fun `centerFractionToTopLeftPx clamps to top-left edge for fraction below visible range`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 0.0f,
            fabSize = 100,
            parentSize = 1000,
        )
        // Center would be 0 → top-left = -50, clamped to 0.
        assertThat(topLeft).isEqualTo(0)
    }

    @Test
    fun `centerFractionToTopLeftPx clamps to bottom-right edge for fraction above visible range`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 1.0f,
            fabSize = 100,
            parentSize = 1000,
        )
        // Center would be 1000 → top-left = 950, max top-left = 900.
        assertThat(topLeft).isEqualTo(900)
    }

    @Test
    fun `centerFractionToTopLeftPx returns 0 when fab larger than parent`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = 0.5f,
            fabSize = 200,
            parentSize = 100,
        )
        assertThat(topLeft).isEqualTo(0)
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
        assertThat(topLeft).isEqualTo(0)
    }

    // Non-finite persisted fractions (RC edge-case audit B7). Exact 0f/1f are already
    // covered above; NaN and ±Infinity were not. A corrupt value (old build / manual
    // DataStore edit) must still yield a valid on-screen top-left, never NaN/∞.

    @Test
    fun `centerFractionToTopLeftPx bounds a NaN fraction to a valid coordinate`() {
        // coerceIn does not clamp NaN, but the final toInt() maps it to 0 — a valid
        // top-left rather than a NaN view coordinate.
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = Float.NaN,
            fabSize = 100,
            parentSize = 1000,
        )
        assertThat(topLeft).isEqualTo(0)
    }

    @Test
    fun `centerFractionToTopLeftPx clamps positive infinity to the bottom-right edge`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = Float.POSITIVE_INFINITY,
            fabSize = 100,
            parentSize = 1000,
        )
        assertThat(topLeft).isEqualTo(900) // max top-left = parent - fab
    }

    @Test
    fun `centerFractionToTopLeftPx clamps negative infinity to the top-left edge`() {
        val topLeft = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = Float.NEGATIVE_INFINITY,
            fabSize = 100,
            parentSize = 1000,
        )
        assertThat(topLeft).isEqualTo(0)
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
        assertThat(fraction).isWithin(0.01f).of(0.3f)
    }

    @Test
    fun `topLeftPxToCenterFraction returns 0_5 for degenerate parent size`() {
        // No division-by-zero, just a safe default.
        val fraction = FabPositionMath.topLeftPxToCenterFraction(
            topLeftPx = 100f,
            fabSize = 50,
            parentSize = 0,
        )
        assertThat(fraction).isEqualTo(0.5f)
    }

    @Test
    fun `topLeftPxToCenterFraction clamps oversized top-left to 1`() {
        val fraction = FabPositionMath.topLeftPxToCenterFraction(
            topLeftPx = 10_000f,
            fabSize = 100,
            parentSize = 1000,
        )
        assertThat(fraction).isEqualTo(1f)
    }

    @Test
    fun `topLeftPxToCenterFraction clamps negative top-left to 0`() {
        val fraction = FabPositionMath.topLeftPxToCenterFraction(
            topLeftPx = -100f,
            fabSize = 100,
            parentSize = 1000,
        )
        assertThat(fraction).isEqualTo(0f)
    }

    // ---------- clampTopLeft ----------

    @Test
    fun `clampTopLeft keeps in-range values untouched`() {
        assertThat(FabPositionMath.clampTopLeft(123f, fabSize = 50, parentSize = 500)).isEqualTo(123f)
    }

    @Test
    fun `clampTopLeft clamps below-zero to zero`() {
        assertThat(FabPositionMath.clampTopLeft(-10f, fabSize = 50, parentSize = 500)).isEqualTo(0f)
    }

    @Test
    fun `clampTopLeft clamps above-max to max`() {
        assertThat(FabPositionMath.clampTopLeft(1000f, fabSize = 50, parentSize = 500)).isEqualTo(450f)
    }

    @Test
    fun `clampTopLeft yields zero when fab equals parent`() {
        assertThat(FabPositionMath.clampTopLeft(100f, fabSize = 500, parentSize = 500)).isEqualTo(0f)
    }

    // ---------- clampTopLeft + insets ----------

    @Test
    fun `clampTopLeft respects insetStart as new minimum`() {
        // Status bar / left cutout reserves the first 80 px on this
        // axis; the FAB must not land below that, even if the user
        // dragged into the bar.
        assertThat(FabPositionMath.clampTopLeft(
                topLeftPx = -10f,
                fabSize = 100,
                parentSize = 1000,
                insetStart = 80,
                insetEnd = 0,
            )).isEqualTo(80f)
    }

    @Test
    fun `clampTopLeft respects insetEnd as new maximum`() {
        // Nav bar / right cutout reserves the last 120 px on this axis.
        // Max top-left = 1000 - 100 - 120 = 780.
        assertThat(FabPositionMath.clampTopLeft(
                topLeftPx = 10_000f,
                fabSize = 100,
                parentSize = 1000,
                insetStart = 0,
                insetEnd = 120,
            )).isEqualTo(780f)
    }

    @Test
    fun `clampTopLeft collapses to insetStart when usable area is smaller than fab`() {
        // Edge case: the FAB literally doesn't fit between the insets.
        // We bias to the start edge (top / left) so it remains tap-
        // reachable.
        assertThat(FabPositionMath.clampTopLeft(
                topLeftPx = 200f,
                fabSize = 900,
                parentSize = 1000,
                insetStart = 80,
                insetEnd = 80,
            )).isEqualTo(80f)
    }

    // ---------- centerFractionToTopLeftPx + insets ----------

    @Test
    fun `centerFractionToTopLeftPx clamps fraction 0 to insetStart not zero`() {
        // A persisted fraction of 0.0 (left/top edge of the screen)
        // must not place the FAB behind the status-bar / left-cutout.
        assertThat(FabPositionMath.centerFractionToTopLeftPx(
                centerFraction = 0.0f,
                fabSize = 100,
                parentSize = 1000,
                insetStart = 80,
                insetEnd = 0,
            )).isEqualTo(80)
    }

    @Test
    fun `centerFractionToTopLeftPx clamps fraction 1 to maximum minus insetEnd`() {
        // Mirror of the above for the trailing edge.
        assertThat(FabPositionMath.centerFractionToTopLeftPx(
                centerFraction = 1.0f,
                fabSize = 100,
                parentSize = 1000,
                insetStart = 0,
                insetEnd = 120,
            )).isEqualTo(780)
    }

    @Test
    fun `centerFractionToTopLeftPx returns insetStart when fab plus insets exceed parent`() {
        assertThat(FabPositionMath.centerFractionToTopLeftPx(
                centerFraction = 0.5f,
                fabSize = 900,
                parentSize = 1000,
                insetStart = 80,
                insetEnd = 80,
            )).isEqualTo(80)
    }
}
