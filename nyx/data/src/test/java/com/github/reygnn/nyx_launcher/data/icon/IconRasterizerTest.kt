package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.google.common.truth.Truth.assertThat
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
}
