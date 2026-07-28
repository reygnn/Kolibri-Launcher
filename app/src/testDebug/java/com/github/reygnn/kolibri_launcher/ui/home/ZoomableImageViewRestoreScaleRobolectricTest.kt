package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ActivityScenario
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric pin for the single-layer restore-scale clamp fix
 * (AUDIT-9 #12). `applyTransform` restores a persisted transform. Its
 * `effectiveMin/MaxScale` bounds are derived from the *current*
 * `_singleScale`; if the restored value is clamped against the stale
 * (pre-restore) scale, a legitimately persisted high zoom is silently
 * capped at `MAX_SCALE` (5x).
 *
 * The bug lives in a custom `View` (`ZoomableImageView`) whose bounds
 * depend on real layout dimensions and drawable intrinsic size — a JVM
 * test can't reach it, and the math isn't extractable without dragging
 * the whole view-state along, so this earns a Robolectric test per
 * CLAUDE.md Rule 10.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class ZoomableImageViewRestoreScaleRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /** A drawable that is much larger than the view → base (cover) scale < 1. */
    private fun sizedDrawable(width: Int, height: Int): Drawable =
        object : ColorDrawable(Color.BLACK) {
            override fun getIntrinsicWidth() = width
            override fun getIntrinsicHeight() = height
        }

    private fun ZoomableImageView.layoutTo(width: Int, height: Int) {
        measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
    }

    @Test
    fun `applyTransform restores a zoom above the pre-restore ceiling instead of clamping to MAX_SCALE`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = ZoomableImageView(activity)
                // View 100x100, drawable 1000x1000 → base scale 0.1.
                view.setImageDrawable(sizedDrawable(1000, 1000))
                view.layoutTo(100, 100)

                // Pre-restore scale is the default 1.0. Old ceiling would be
                // maxOf(MAX_SCALE=5, maxOf(base=0.1, current=1.0) * 3) = 5.
                assertEquals(1.0f, view.currentScale, 0.0001f)

                // Restore a zoom of 8x — reachable interactively because the
                // ceiling grows with the current scale (5 → 15 → …), so 8x is
                // a legitimately persisted state.
                view.applyTransform(8.0f, 0f, 0f)

                assertEquals(
                    "Restored zoom must be honored, not clamped to MAX_SCALE (5x). " +
                        "The bounds must reference the restored scale, not the stale pre-restore one.",
                    8.0f,
                    view.currentScale,
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun `applyTransform restores a modest in-range zoom unchanged`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = ZoomableImageView(activity)
                view.setImageDrawable(sizedDrawable(1000, 1000))
                view.layoutTo(100, 100)

                view.applyTransform(3.0f, 12f, -7f)

                assertEquals(3.0f, view.currentScale, 0.0001f)
                assertEquals(12f, view.currentTranslateX, 0.0001f)
                assertEquals(-7f, view.currentTranslateY, 0.0001f)
            }
        }
    }

    @Test
    fun `applyTransform sanitizes corrupt persisted scales to the default instead of building a degenerate matrix`() {
        // Restore is intentionally not magnitude-clamped (see the primary
        // test), so the only guard left is against corrupt persisted input:
        // non-finite or non-positive scales that would otherwise propagate
        // into the image matrix as Inf/NaN/negative. Each must fall back to
        // DEFAULT_SCALE (1.0).
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val corruptScales = listOf(
                    Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY,
                    Float.NaN,
                    -2.0f,
                    0.0f,
                )
                corruptScales.forEach { corrupt ->
                    val view = ZoomableImageView(activity)
                    view.setImageDrawable(sizedDrawable(1000, 1000))
                    view.layoutTo(100, 100)

                    view.applyTransform(corrupt, 0f, 0f)

                    assertEquals(
                        "Corrupt persisted scale $corrupt must fall back to DEFAULT_SCALE (1.0), " +
                            "not propagate into the image matrix.",
                        1.0f,
                        view.currentScale,
                        0.0001f,
                    )
                }
            }
        }
    }
}
