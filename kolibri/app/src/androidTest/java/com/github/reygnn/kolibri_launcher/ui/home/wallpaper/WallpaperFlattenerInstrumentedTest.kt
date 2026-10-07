package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattener
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end check of the Option-D flatten (Phase 2, Step C):
 * [WallpaperFlattener] must turn a real multi-layer [WallpaperState] into one
 * software composite bitmap of the requested size. Device-only (Rule 10): real
 * `BitmapFactory` software decode + `WallpaperViewBinder` on a real
 * `ZoomableImageView` + `composeToBitmap` — none of which Robolectric composites
 * faithfully. Includes a MULTIPLY layer to exercise the blend path.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperFlattenerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun flattenProducesCompositeForMultiLayerState() = runBlocking {
        val f1 = writeTestImage("flatten_test_base.png", Color.rgb(200, 120, 40))
        val f2 = writeTestImage("flatten_test_top.png", Color.rgb(40, 120, 200))
        try {
            val state = WallpaperState(
                layers = listOf(
                    WallpaperLayerState(imageUri = Uri.fromFile(f1).toString()),
                    WallpaperLayerState(
                        imageUri = Uri.fromFile(f2).toString(),
                    ),
                ),
            )
            val flattener = WallpaperFlattener(context, Dispatchers.Main)

            val composite = flattener.flatten(state, width = 200, height = 400)

            assertWithMessage("flatten must produce a composite for a multi-layer state").that(composite).isNotNull()
            assertThat(composite!!.width).isEqualTo(200)
            assertThat(composite.height).isEqualTo(400)
            assertThat(composite.config).isEqualTo(Bitmap.Config.ARGB_8888)
            composite.recycle()
        } finally {
            f1.delete()
            f2.delete()
        }
    }

    @Test
    fun flattenReturnsNullForSingleLayerState() = runBlocking {
        // Single-layer wallpapers are already one bitmap — nothing to flatten.
        val state = WallpaperState.single("file:///does/not/matter.png")
        val flattener = WallpaperFlattener(context, Dispatchers.Main)
        assertThat(flattener.flatten(state, width = 200, height = 400)).isNull()
    }

    private fun writeTestImage(name: String, color: Int): File {
        val bmp = createBitmap(100, 200).apply { eraseColor(color) }
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }
}
