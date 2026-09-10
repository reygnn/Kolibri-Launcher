package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ActivityScenario
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * The corrupt-scale test above always passes translate = 0, so it never
     * covered a corrupt persisted TRANSLATE — which feeds `postTranslate()` and
     * builds a degenerate matrix just as a bad scale does. With a VALID scale but
     * a non-finite translate, the scale guard alone lets the bad translate through;
     * each must fall back to 0.
     */
    @Test
    fun `applyTransform sanitizes a corrupt persisted translate to zero (single-layer)`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val corruptTranslates = listOf(
                    Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY,
                    Float.NaN,
                )
                corruptTranslates.forEach { corrupt ->
                    val view = ZoomableImageView(activity)
                    view.setImageDrawable(sizedDrawable(1000, 1000))
                    view.layoutTo(100, 100)

                    view.applyTransform(2.0f, corrupt, corrupt)

                    // Scale is valid and honored; only the translate is sanitized.
                    assertEquals(2.0f, view.currentScale, 0.0001f)
                    assertEquals(
                        "Corrupt translateX $corrupt must fall back to 0, not reach the matrix.",
                        0f, view.currentTranslateX, 0.0001f,
                    )
                    assertEquals(
                        "Corrupt translateY $corrupt must fall back to 0, not reach the matrix.",
                        0f, view.currentTranslateY, 0.0001f,
                    )
                }
            }
        }
    }

    /**
     * The single-layer path sanitizes corrupt input; the multi-layer path used
     * `coerceIn`, which passes NaN through unchanged (`NaN.coerceIn(a,b) == NaN`),
     * and assigned translate raw — and no test exercised it. This pins the parity:
     * on the active layer, a non-finite scale falls back to a finite value and a
     * non-finite translate falls back to 0.
     */
    @Test
    fun `applyTransform sanitizes corrupt scale and translate in multi-layer mode`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = ZoomableImageView(activity)
                view.layoutTo(100, 100)
                // A real bitmap layer becomes the active layer -> multi-layer mode.
                val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                view.addLayer(bitmap)
                assertTrue("addLayer must enter multi-layer mode", view.isMultiLayerMode)

                // Corrupt scale: coerceIn would keep NaN; must become finite/positive.
                view.applyTransform(Float.NaN, 0f, 0f)
                assertTrue(
                    "Corrupt multi-layer scale must not survive as non-finite",
                    view.currentScale.isFinite() && view.currentScale > 0f,
                )

                // Corrupt translate on the active layer: must fall back to 0.
                view.applyTransform(1.0f, Float.NaN, Float.POSITIVE_INFINITY)
                assertEquals(0f, view.currentTranslateX, 0.0001f)
                assertEquals(0f, view.currentTranslateY, 0.0001f)
            }
        }
    }
}
