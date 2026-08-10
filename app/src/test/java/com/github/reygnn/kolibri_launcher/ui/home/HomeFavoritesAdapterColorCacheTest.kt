package com.github.reygnn.kolibri_launcher.ui.home

import androidx.core.graphics.ColorUtils
import com.github.reygnn.kolibri_launcher.core.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards for the single-entry press-color cache (AUDIT-14 F3, part 2):
 * [HomeFavoritesAdapter.subtlePressColor] must reuse one [android.content.res.ColorStateList]
 * across binds that share a text color (the styling-rebind case, where every
 * row carries the same `styling.textColor`), and must still produce the correct
 * default/pressed colors.
 *
 * Robolectric is required only because `ColorStateList` needs the Android
 * runtime; the caching logic itself is plain Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class HomeFavoritesAdapterColorCacheTest {

    private val adapter = HomeFavoritesAdapter(onAppClick = {}, onAppLongClick = {})

    @Test
    fun `same textColor reuses the same ColorStateList instance`() {
        val color = 0xFF3366CC.toInt()

        val first = adapter.subtlePressColor(color)
        val second = adapter.subtlePressColor(color)

        // Cache hit: the styling rebind binds N rows with one color -> one alloc.
        assertSame(first, second)
    }

    @Test
    fun `a different textColor produces a new instance and re-request re-allocates`() {
        val colorA = 0xFF3366CC.toInt()
        val colorB = 0xFFCC3366.toInt()

        val a = adapter.subtlePressColor(colorA)
        val b = adapter.subtlePressColor(colorB)
        assertNotSame(a, b)

        // Single-entry cache: colorB evicted colorA, so colorA re-allocates.
        val aAgain = adapter.subtlePressColor(colorA)
        assertNotSame(a, aAgain)
    }

    @Test
    fun `default state uses the normal color and pressed state uses the alpha-dimmed color`() {
        val normal = 0xFF3366CC.toInt()

        val stateList = adapter.subtlePressColor(normal)

        assertEquals(normal, stateList.defaultColor)
        val pressed = stateList.getColorForState(
            intArrayOf(android.R.attr.state_pressed),
            normal,
        )
        assertEquals(
            ColorUtils.setAlphaComponent(normal, AppConstants.PRESSED_STATE_ALPHA),
            pressed,
        )
    }
}
