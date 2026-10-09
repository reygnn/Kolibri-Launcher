package com.github.reygnn.launcher.common.ui.wallpaper

import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperLayerPlacement.Placement
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [WallpaperLayerPlacement] (SPEC_NYX_REWRITE Stufe 2): the pure placement of one layer —
 * center-crop for an untransformed layer, a saved transform compensated for the decode sample
 * size, clamped to the per-layer zoom range and sanitized. That it matches the live view bit for
 * bit is pinned on the device by `WallpaperCompositorParityInstrumentedTest`; here the rules.
 */
class WallpaperLayerPlacementTest {

    private fun transform(scale: Float, x: Float = 0f, y: Float = 0f, captured: Int? = 1) =
        LayerPropertyUpdate.Transform(scale, x, y, captured)

    private fun place(
        transform: LayerPropertyUpdate.Transform?,
        bitmapW: Int,
        bitmapH: Int,
        sampleSize: Int = 1,
        viewW: Int = 300,
        viewH: Int = 500,
    ) = WallpaperLayerPlacement.place(transform, bitmapW, bitmapH, sampleSize, 0, 0, viewW, viewH)

    @Test
    fun an_untransformed_landscape_layer_covers_the_height_and_is_centered_horizontally() {
        assertThat(place(null, bitmapW = 400, bitmapH = 200))
            .isEqualTo(Placement(scale = 2.5f, translateX = -350f, translateY = 0f))
    }

    @Test
    fun an_untransformed_portrait_layer_covers_the_width_and_is_centered_vertically() {
        assertThat(place(null, bitmapW = 100, bitmapH = 1000))
            .isEqualTo(Placement(scale = 3f, translateX = 0f, translateY = -1250f))
    }

    @Test
    fun a_saved_transform_inside_the_zoom_range_is_kept() {
        assertThat(place(transform(1.5f, x = 10f, y = -20f), bitmapW = 300, bitmapH = 500))
            .isEqualTo(Placement(scale = 1.5f, translateX = 10f, translateY = -20f))
    }

    @Test
    fun a_scale_captured_at_another_sample_size_is_compensated_and_the_translate_kept() {
        // Captured against a bitmap downsampled by 2, rendered at full resolution: half the scale.
        assertThat(place(transform(2f, x = 7f, y = 9f, captured = 2), bitmapW = 300, bitmapH = 500, sampleSize = 1))
            .isEqualTo(Placement(scale = 1f, translateX = 7f, translateY = 9f))
        // Captured at full resolution, rendered downsampled by 4: four times the scale.
        assertThat(place(transform(0.5f, captured = 1), bitmapW = 300, bitmapH = 500, sampleSize = 4).scale)
            .isEqualTo(2f)
    }

    @Test
    fun a_scale_below_the_range_is_clamped_to_the_floor() {
        // Base 5 (100 px image in a 300x500 view) → floor min(0.1, 5 × 0.05) = 0.1.
        assertThat(place(transform(0.001f), bitmapW = 100, bitmapH = 100).scale).isEqualTo(0.1f)
    }

    @Test
    fun a_scale_above_the_range_is_clamped_to_the_ceiling_relative_to_the_base() {
        // Base 5 → ceiling max(10, 5 × 3) = 15.
        assertThat(place(transform(80f), bitmapW = 100, bitmapH = 100).scale).isEqualTo(15f)
        // Base 0.1 (huge image) → ceiling max(10, 0.3) = 10.
        assertThat(place(transform(50f), bitmapW = 3000, bitmapH = 5000).scale).isEqualTo(10f)
    }

    @Test
    fun corrupt_values_are_sanitized() {
        assertThat(place(transform(Float.NaN, x = Float.POSITIVE_INFINITY, y = Float.NaN), bitmapW = 300, bitmapH = 500))
            .isEqualTo(Placement(scale = 1f, translateX = 0f, translateY = 0f))
        assertThat(place(transform(-2f), bitmapW = 300, bitmapH = 500).scale).isEqualTo(1f)
    }

    @Test
    fun the_base_scale_is_one_when_the_view_or_the_bitmap_has_no_size() {
        assertThat(WallpaperLayerPlacement.baseScale(100, 100, 0, 500)).isEqualTo(1f)
        assertThat(WallpaperLayerPlacement.baseScale(0, 100, 300, 500)).isEqualTo(1f)
        assertThat(WallpaperLayerPlacement.baseScale(100, 100, 300, 500)).isEqualTo(5f)
    }
}
