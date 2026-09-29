package com.github.reygnn.launcher.common.ui.gesture

import com.github.reygnn.launcher.common.ui.gesture.SwipeGestureAnalyzer.SwipeResult
import com.google.common.truth.Truth.assertThat
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
        assertThat(analyzer.analyze(51f, 0f, 51f, 0f)).isEqualTo(SwipeResult.TOWARDS_RIGHT)

    @Test fun `diff 50 exact threshold is ignored`() =
        assertThat(analyzer.analyze(50f, 0f, 51f, 0f)).isEqualTo(SwipeResult.IGNORED)

    @Test fun `velocity 50 exact threshold is ignored`() =
        assertThat(analyzer.analyze(51f, 0f, 50f, 0f)).isEqualTo(SwipeResult.IGNORED)

    @Test fun `diff 49 just below threshold is ignored`() =
        assertThat(analyzer.analyze(49f, 0f, 100f, 0f)).isEqualTo(SwipeResult.IGNORED)

    // ---- direction + dominance ----

    @Test fun `clear swipe LEFT`() =
        assertThat(analyzer.analyze(-60f, 0f, 60f, 0f)).isEqualTo(SwipeResult.TOWARDS_LEFT)

    @Test fun `clear swipe UP`() =
        assertThat(analyzer.analyze(0f, -60f, 0f, 60f)).isEqualTo(SwipeResult.UP)

    @Test fun `clear swipe DOWN`() =
        assertThat(analyzer.analyze(0f, 60f, 0f, 60f)).isEqualTo(SwipeResult.DOWN)

    @Test fun `diagonal favors dominant axis X`() =
        assertThat(analyzer.analyze(100f, 60f, 100f, 100f)).isEqualTo(SwipeResult.TOWARDS_RIGHT)

    @Test fun `diagonal favors dominant axis Y`() =
        assertThat(analyzer.analyze(60f, 100f, 100f, 100f)).isEqualTo(SwipeResult.DOWN)

    // ---- 1.5x dominance: near-diagonal is rejected ----

    @Test fun `near-diagonal under 1_5x dominance is ignored`() =
        assertThat(dominant.analyze(250f, 300f, 250f, 300f)).isEqualTo(SwipeResult.IGNORED)
}
