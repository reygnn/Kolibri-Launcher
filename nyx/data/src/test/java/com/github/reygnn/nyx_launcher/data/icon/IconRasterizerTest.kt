package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.google.common.truth.Truth.assertThat
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric: rasterizing produces a square bitmap of the requested size. */
@RunWith(RobolectricTestRunner::class)
class IconRasterizerTest {

    @Test
    fun rasterizes_to_requested_size() {
        val bitmap = IconRasterizer().rasterize(ColorDrawable(Color.RED), sizePx = 96)
        assertThat(bitmap.width).isEqualTo(96)
        assertThat(bitmap.height).isEqualTo(96)
        assertThat(bitmap.isRecycled).isFalse()
    }

    @Test
    fun monochrome_without_a_monochrome_layer_falls_back_to_grayscale() {
        // A plain ColorDrawable is not an AdaptiveIconDrawable, so the monochrome
        // path must delegate to rasterizeGrayscale — the feature's headline change
        // (fall back to grayscale, NOT the colour icon). Pins the dispatch decision;
        // pixel-level desaturation is real Canvas compositing (not asserted here).
        val rasterizer = spyk(IconRasterizer())
        val drawable = ColorDrawable(Color.RED)

        rasterizer.rasterizeMonochrome(drawable, sizePx = 96, background = Color.BLACK, foreground = Color.WHITE)

        verify(exactly = 1) { rasterizer.rasterizeGrayscale(drawable, 96) }
    }

    @Test
    fun grayscale_rasterizes_to_requested_size() {
        // Pixel-level desaturation is real Canvas/ColorFilter compositing (saveLayer),
        // which Robolectric does not reproduce faithfully — the variant dispatch is
        // pinned in LauncherAppsIconSourceTest; here we only assert a valid bitmap.
        val bitmap = IconRasterizer().rasterizeGrayscale(ColorDrawable(Color.RED), sizePx = 96)
        assertThat(bitmap.width).isEqualTo(96)
        assertThat(bitmap.height).isEqualTo(96)
        assertThat(bitmap.isRecycled).isFalse()
    }
}
