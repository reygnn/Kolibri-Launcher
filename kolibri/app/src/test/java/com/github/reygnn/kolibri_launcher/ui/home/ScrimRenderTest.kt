package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.core.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrimRenderTest {

    @Test
    fun `zero alpha yields null (scrim GONE)`() {
        assertNull(ScrimRender.colorOrNull(alpha = 0f, isEditMode = false))
    }

    @Test
    fun `edit mode yields null even at max alpha`() {
        assertNull(ScrimRender.colorOrNull(alpha = 1f, isEditMode = true))
    }

    @Test
    fun `alpha rounding down to a zero byte yields null`() {
        // 0.001 * 255 = 0.255 → rounds to 0 → fully transparent → GONE.
        assertNull(ScrimRender.colorOrNull(alpha = 0.001f, isEditMode = false))
    }

    @Test
    fun `typical alpha bakes into the alpha byte over opaque black`() {
        // 0.2 * 255 = 51 → 0x33; RGB stays 0x000000.
        assertEquals(0x33000000.toInt(), ScrimRender.colorOrNull(alpha = 0.2f, isEditMode = false))
    }

    @Test
    fun `max alpha is fully opaque black`() {
        assertEquals(0xFF000000.toInt(), ScrimRender.colorOrNull(alpha = 1f, isEditMode = false))
    }

    @Test
    fun `alpha above 1 is clamped to opaque`() {
        assertEquals(0xFF000000.toInt(), ScrimRender.colorOrNull(alpha = 5f, isEditMode = false))
    }

    @Test
    fun `negative alpha is clamped to null`() {
        assertNull(ScrimRender.colorOrNull(alpha = -1f, isEditMode = false))
    }

    // --- snapAlphaToSliderGrid ---

    @Test
    fun `on-grid value passes through unchanged`() {
        assertEquals(0.25f, ScrimRender.snapAlphaToSliderGrid(0.25f), 0.0001f)
    }

    @Test
    fun `off-grid value snaps to nearest step`() {
        // 0.42 → nearest 0.05 step = 0.40
        assertEquals(0.40f, ScrimRender.snapAlphaToSliderGrid(0.42f), 0.0001f)
        // 0.43 → nearest 0.05 step = 0.45
        assertEquals(0.45f, ScrimRender.snapAlphaToSliderGrid(0.43f), 0.0001f)
    }

    @Test
    fun `below-min snaps to min`() {
        assertEquals(
            AppConstants.WALLPAPER_SCRIM_ALPHA_MIN,
            ScrimRender.snapAlphaToSliderGrid(-1f),
            0.0001f
        )
    }

    @Test
    fun `above-max snaps to max`() {
        assertEquals(
            AppConstants.WALLPAPER_SCRIM_ALPHA_MAX,
            ScrimRender.snapAlphaToSliderGrid(99f),
            0.0001f
        )
    }

    @Test
    fun `snapped result is always within slider range`() {
        var v = AppConstants.WALLPAPER_SCRIM_ALPHA_MIN
        while (v <= AppConstants.WALLPAPER_SCRIM_ALPHA_MAX + 0.001f) {
            val snapped = ScrimRender.snapAlphaToSliderGrid(v)
            assertEquals(true, snapped >= AppConstants.WALLPAPER_SCRIM_ALPHA_MIN)
            assertEquals(true, snapped <= AppConstants.WALLPAPER_SCRIM_ALPHA_MAX)
            v += 0.017f
        }
    }
}
