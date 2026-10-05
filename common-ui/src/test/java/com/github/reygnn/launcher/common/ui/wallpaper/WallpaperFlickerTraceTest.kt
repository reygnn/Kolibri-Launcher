package com.github.reygnn.launcher.common.ui.wallpaper

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * [WallpaperFlickerTrace.hasLayerWithoutBitmap] (3b-0, E3) pins what an "affected frame" is: at
 * least one layer of the shown state has no drawable bitmap. The Trace counter itself needs a
 * device; this pure part does not.
 */
class WallpaperFlickerTraceTest {

    private fun bitmap(recycled: Boolean = false) = mockk<Bitmap> { every { isRecycled } returns recycled }

    @Test
    fun all_layers_with_a_bitmap_is_no_affected_frame() {
        val layers = listOf(WallpaperLayer(bitmap = bitmap()), WallpaperLayer(bitmap = bitmap()))

        assertThat(WallpaperFlickerTrace.hasLayerWithoutBitmap(layers)).isFalse()
    }

    @Test
    fun one_layer_not_loaded_yet_makes_the_frame_affected() {
        val layers = listOf(WallpaperLayer(bitmap = bitmap()), WallpaperLayer(bitmap = null))

        assertThat(WallpaperFlickerTrace.hasLayerWithoutBitmap(layers)).isTrue()
    }

    @Test
    fun a_recycled_bitmap_counts_as_missing() {
        val layers = listOf(WallpaperLayer(bitmap = bitmap(recycled = true)), WallpaperLayer(bitmap = bitmap()))

        assertThat(WallpaperFlickerTrace.hasLayerWithoutBitmap(layers)).isTrue()
    }
}
