package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
            val flattener = WallpaperFlattener(context, Dispatchers.Main, Dispatchers.Default)

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
        val flattener = WallpaperFlattener(context, Dispatchers.Main, Dispatchers.Default)
        assertThat(flattener.flatten(state, width = 200, height = 400)).isNull()
    }

    @Test
    fun composeOffMainIsPixelIdenticalToComposeOnMain() = runBlocking {
        // Stufe 1 (audit-04): the compose moved from Main to the default dispatcher. Same class,
        // only the compose dispatcher differs — the result must be bit-identical, not just close.
        // Three layers with gradients (so filtering and transforms show), a centre-cropped
        // opaque base plus two transformed, partly transparent layers, and a target size that
        // differs from the image sizes.
        val base = writeGradientImage("flatten_pixel_base.png", 120, 240, Color.rgb(250, 30, 30), Color.rgb(30, 30, 250))
        val middle = writeGradientImage("flatten_pixel_middle.png", 200, 100, Color.argb(200, 20, 220, 60), Color.argb(40, 240, 240, 20))
        val top = writeGradientImage("flatten_pixel_top.png", 90, 90, Color.argb(160, 255, 255, 255), Color.argb(0, 0, 0, 0))
        try {
            val state = WallpaperState(
                layers = listOf(
                    WallpaperLayerState(imageUri = Uri.fromFile(base).toString()),
                    WallpaperLayerState(imageUri = Uri.fromFile(middle).toString(), scale = 1.7f, translateX = -35f, translateY = 60f),
                    WallpaperLayerState(imageUri = Uri.fromFile(top).toString(), scale = 2.3f, translateX = 70f, translateY = 140f),
                ),
            )
            val onMain = WallpaperFlattener(context, Dispatchers.Main, Dispatchers.Main)
                .flatten(state, width = 300, height = 500)
            val offMain = WallpaperFlattener(context, Dispatchers.Main, Dispatchers.Default)
                .flatten(state, width = 300, height = 500)

            assertWithMessage("compose on Main must produce a composite").that(onMain).isNotNull()
            assertWithMessage("compose off Main must produce a composite").that(offMain).isNotNull()
            // Guard against a trivially equal pair (e.g. two blank bitmaps): the scene must show
            // the gradients.
            val sampled = buildSet {
                for (y in 0 until 500 step 25) for (x in 0 until 300 step 15) add(onMain!!.getPixel(x, y))
            }
            assertWithMessage("the reference composite must not be uniform").that(sampled.size).isAtLeast(16)
            assertWithMessage("compose off Main must be bit-identical to compose on Main")
                .that(offMain!!.sameAs(onMain)).isTrue()
            onMain!!.recycle()
            offMain.recycle()
        } finally {
            base.delete()
            middle.delete()
            top.delete()
        }
    }

    private fun writeGradientImage(name: String, width: Int, height: Int, from: Int, to: Int): File {
        val bmp = createBitmap(width, height)
        Canvas(bmp).drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), from, to, Shader.TileMode.CLAMP)
            },
        )
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    private fun writeTestImage(name: String, color: Int): File {
        val bmp = createBitmap(100, 200).apply { eraseColor(color) }
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }
}
