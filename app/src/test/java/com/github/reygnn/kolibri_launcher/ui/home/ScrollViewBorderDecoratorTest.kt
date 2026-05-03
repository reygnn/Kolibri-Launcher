package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric tests for [ScrollViewBorderDecorator]. The decorator is dormant
 * in production (`enabled = false` by default); these tests instantiate with
 * `enabled = true` to exercise the body that production keeps frozen for
 * future split-mode reactivation. Audit §3.1.
 *
 * Robolectric (no Hilt) per the project convention: the decorator only needs
 * a real Resources, real GradientDrawable, and real View setters — none of
 * which are honestly mockable on plain JVM. No `@Config(application = ...)`
 * is set, so the project-wide `robolectric.properties` default
 * (android.app.Application) applies.
 *
 * Dimension assertions read the actual values from R.dimen at test time
 * rather than hard-coding pixels, so the tests stay green if Robolectric's
 * default density ever changes.
 */
@RunWith(RobolectricTestRunner::class)
class ScrollViewBorderDecoratorTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var context: Context
    private lateinit var target: ViewGroup

    private val expectedPaddingPx: Int by lazy {
        context.resources.getDimensionPixelSize(R.dimen.split_screen_border_inset)
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        target = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                // Pre-set non-zero margins so we can verify the decorator
                // zeroes them when enabled — and leaves them alone when
                // disabled.
                setMargins(50, 50, 50, 50)
            }
        }
    }

    // ------------------------------------------------------------------------
    // disabled (default) — apply() / remove() are no-ops
    // ------------------------------------------------------------------------

    @Test
    fun `apply with enabled false is a no-op`() {
        val decorator = ScrollViewBorderDecorator() // default = false
        decorator.apply(target, Color.WHITE)

        assertNull("background must remain null", target.background)
        assertEquals(0, target.paddingTop)
        assertEquals(50, (target.layoutParams as LinearLayout.LayoutParams).topMargin)
    }

    @Test
    fun `remove with enabled false is a no-op`() {
        // Pre-state: pretend something else set the background and padding.
        val placeholder = GradientDrawable()
        target.background = placeholder
        target.setPadding(10, 10, 10, 10)

        val decorator = ScrollViewBorderDecorator(enabled = false)
        decorator.remove(target)

        assertSame("background must remain", placeholder, target.background)
        assertEquals(10, target.paddingTop)
    }

    // ------------------------------------------------------------------------
    // enabled — apply() exercises the body
    // ------------------------------------------------------------------------

    @Test
    fun `apply with enabled true sets a GradientDrawable background`() {
        val decorator = ScrollViewBorderDecorator(enabled = true)
        decorator.apply(target, Color.WHITE)

        assertNotNull("background must be set", target.background)
        assertTrue(
            "background must be a GradientDrawable, got ${target.background?.javaClass?.simpleName}",
            target.background is GradientDrawable,
        )
    }

    @Test
    fun `apply with enabled true applies padding and clipToPadding`() {
        val decorator = ScrollViewBorderDecorator(enabled = true)
        decorator.apply(target, Color.WHITE)

        // Padding: 0 on the left, borderPadding on top/right/bottom.
        assertEquals(0, target.paddingLeft)
        assertEquals(expectedPaddingPx, target.paddingTop)
        assertEquals(expectedPaddingPx, target.paddingRight)
        assertEquals(expectedPaddingPx, target.paddingBottom)
        assertTrue(target.clipToPadding)
    }

    @Test
    fun `apply with enabled true zeroes LinearLayout margins`() {
        val decorator = ScrollViewBorderDecorator(enabled = true)
        decorator.apply(target, Color.WHITE)

        val params = target.layoutParams as LinearLayout.LayoutParams
        assertEquals(0, params.topMargin)
        assertEquals(0, params.bottomMargin)
        assertEquals(0, params.leftMargin)
        assertEquals(0, params.rightMargin)
    }

    @Test
    fun `apply called twice reuses the cached drawable`() {
        val decorator = ScrollViewBorderDecorator(enabled = true)
        decorator.apply(target, Color.WHITE)
        val first = target.background
        decorator.apply(target, Color.WHITE)
        val second = target.background

        assertSame("second apply must reuse the cached GradientDrawable", first, second)
    }

    @Test
    fun `apply on non-LinearLayout layoutParams still sets padding and background`() {
        // FrameLayout.LayoutParams extends MarginLayoutParams but is NOT
        // LinearLayout.LayoutParams — the safe-cast inside apply() returns
        // null and the function early-returns AFTER padding is set.
        target.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )

        val decorator = ScrollViewBorderDecorator(enabled = true)
        decorator.apply(target, Color.WHITE)

        assertNotNull("background must still be set on non-LinearLayout child", target.background)
        assertEquals(expectedPaddingPx, target.paddingTop)
        assertTrue(target.clipToPadding)
    }

    // ------------------------------------------------------------------------
    // enabled — remove() reverts the body
    // ------------------------------------------------------------------------

    @Test
    fun `remove with enabled true clears background, padding, and margins`() {
        // Pre-state: pretend apply was called.
        target.background = GradientDrawable()
        target.setPadding(8, 8, 8, 8)
        (target.layoutParams as LinearLayout.LayoutParams).setMargins(50, 50, 50, 50)

        val decorator = ScrollViewBorderDecorator(enabled = true)
        decorator.remove(target)

        assertNull(target.background)
        assertEquals(0, target.paddingLeft)
        assertEquals(0, target.paddingTop)
        assertEquals(0, target.paddingRight)
        assertEquals(0, target.paddingBottom)
        val params = target.layoutParams as LinearLayout.LayoutParams
        assertEquals(0, params.topMargin)
        assertEquals(0, params.leftMargin)
    }

    // ------------------------------------------------------------------------
    // clear() — drops the cached drawable
    // ------------------------------------------------------------------------

    @Test
    fun `clear forces a fresh drawable on the next apply`() {
        val decorator = ScrollViewBorderDecorator(enabled = true)
        decorator.apply(target, Color.WHITE)
        val first = target.background

        decorator.clear()
        decorator.apply(target, Color.WHITE)
        val second = target.background

        assertNotSame(
            "clear must drop the cache so apply allocates a new GradientDrawable",
            first,
            second,
        )
    }
}
