package com.github.reygnn.launcher.common.ui.gesture

import com.github.reygnn.launcher.common.ui.gesture.SwipeGestureAnalyzer.SwipeResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic pin for the shared swipe analyzer — no Android dependency, runs
 * as a plain JVM unit test. Folds in the boundary + dominance cases that
 * previously lived in Kolibri's SwipeGestureAnalyzerTest.
 */
class SwipeGestureAnalyzerTest {

    // Round-number thresholds (50/50) for legible boundary assertions; the
    // analyzer is unit-agnostic so production calibration is irrelevant here.
    // Default dominanceFactor = 1f exercises the binary axis check.
    private val analyzer = SwipeGestureAnalyzer(
        distanceThreshold = 50f,
        velocityThreshold = 50f,
    )

    // Explicit 1.5x dominance to exercise the diagonal-ambiguity path.
    private val dominant = SwipeGestureAnalyzer(
        distanceThreshold = 50f,
        velocityThreshold = 50f,
        dominanceFactor = 1.5f,
    )

    // ---- boundary ----

    @Test fun `diff 51 just above threshold is valid`() =
        assertEquals(SwipeResult.TOWARDS_RIGHT, analyzer.analyze(51f, 0f, 51f, 0f))

    @Test fun `diff 50 exact threshold is ignored`() =
        assertEquals(SwipeResult.IGNORED, analyzer.analyze(50f, 0f, 51f, 0f))

    @Test fun `velocity 50 exact threshold is ignored`() =
        assertEquals(SwipeResult.IGNORED, analyzer.analyze(51f, 0f, 50f, 0f))

    @Test fun `diff 49 just below threshold is ignored`() =
        assertEquals(SwipeResult.IGNORED, analyzer.analyze(49f, 0f, 100f, 0f))

    // ---- direction + dominance ----

    @Test fun `clear swipe LEFT`() =
        assertEquals(SwipeResult.TOWARDS_LEFT, analyzer.analyze(-60f, 0f, 60f, 0f))

    @Test fun `clear swipe UP`() =
        assertEquals(SwipeResult.UP, analyzer.analyze(0f, -60f, 0f, 60f))

    @Test fun `clear swipe DOWN`() =
        assertEquals(SwipeResult.DOWN, analyzer.analyze(0f, 60f, 0f, 60f))

    @Test fun `diagonal favors dominant axis X`() =
        assertEquals(SwipeResult.TOWARDS_RIGHT, analyzer.analyze(100f, 60f, 100f, 100f))

    @Test fun `diagonal favors dominant axis Y`() =
        assertEquals(SwipeResult.DOWN, analyzer.analyze(60f, 100f, 100f, 100f))

    // ---- 1.5x dominance: near-diagonal is rejected ----

    @Test fun `near-diagonal under 1_5x dominance is ignored`() =
        assertEquals(SwipeResult.IGNORED, dominant.analyze(250f, 300f, 250f, 300f))
}
