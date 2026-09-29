package com.github.reygnn.launcher.core.wallpaper

import com.github.reygnn.launcher.core.AppConstants
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrimRenderTest {

    @Test
    fun `zero alpha yields null (scrim GONE)`() {
        assertThat(ScrimRender.colorOrNull(alpha = 0f, isEditMode = false)).isNull()
    }

    @Test
    fun `edit mode yields null even at max alpha`() {
        assertThat(ScrimRender.colorOrNull(alpha = 1f, isEditMode = true)).isNull()
    }

    @Test
    fun `alpha rounding down to a zero byte yields null`() {
        // 0.001 * 255 = 0.255 → rounds to 0 → fully transparent → GONE.
        assertThat(ScrimRender.colorOrNull(alpha = 0.001f, isEditMode = false)).isNull()
    }

    @Test
    fun `typical alpha bakes into the alpha byte over opaque black`() {
        // 0.2 * 255 = 51 → 0x33; RGB stays 0x000000.
        assertThat(ScrimRender.colorOrNull(alpha = 0.2f, isEditMode = false)).isEqualTo(0x33000000.toInt())
    }

    @Test
    fun `max alpha is fully opaque black`() {
        assertThat(ScrimRender.colorOrNull(alpha = 1f, isEditMode = false)).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `alpha above 1 is clamped to opaque`() {
        assertThat(ScrimRender.colorOrNull(alpha = 5f, isEditMode = false)).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `negative alpha is clamped to null`() {
        assertThat(ScrimRender.colorOrNull(alpha = -1f, isEditMode = false)).isNull()
    }

    // --- snapAlphaToSliderGrid ---

    @Test
    fun `on-grid value passes through unchanged`() {
        assertThat(ScrimRender.snapAlphaToSliderGrid(0.25f)).isWithin(0.0001f).of(0.25f)
    }

    @Test
    fun `off-grid value snaps to nearest step`() {
        // 0.42 → nearest 0.05 step = 0.40
        assertThat(ScrimRender.snapAlphaToSliderGrid(0.42f)).isWithin(0.0001f).of(0.40f)
        // 0.43 → nearest 0.05 step = 0.45
        assertThat(ScrimRender.snapAlphaToSliderGrid(0.43f)).isWithin(0.0001f).of(0.45f)
    }

    @Test
    fun `below-min snaps to min`() {
        assertThat(ScrimRender.snapAlphaToSliderGrid(-1f)).isWithin(0.0001f).of(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN)
    }

    @Test
    fun `above-max snaps to max`() {
        assertThat(ScrimRender.snapAlphaToSliderGrid(99f)).isWithin(0.0001f).of(AppConstants.WALLPAPER_SCRIM_ALPHA_MAX)
    }

    @Test
    fun `snapped result is always within slider range`() {
        var v = AppConstants.WALLPAPER_SCRIM_ALPHA_MIN
        while (v <= AppConstants.WALLPAPER_SCRIM_ALPHA_MAX + 0.001f) {
            val snapped = ScrimRender.snapAlphaToSliderGrid(v)
            assertThat(snapped >= AppConstants.WALLPAPER_SCRIM_ALPHA_MIN).isEqualTo(true)
            assertThat(snapped <= AppConstants.WALLPAPER_SCRIM_ALPHA_MAX).isEqualTo(true)
            v += 0.017f
        }
    }
}
