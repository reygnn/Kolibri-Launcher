package com.github.reygnn.launcher.common.ui.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.view.View
import androidx.core.graphics.createBitmap
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.FileOutputStream

/**
 * The parity scenes of the view-free flatten (SPEC_NYX_REWRITE Stufe 2, 3b/35) — ONE source for
 * `WallpaperCompositorParityRobolectricTest` (common-ui, host) and
 * `WallpaperCompositorParityInstrumentedTest` (kolibri/app, A17), so both check the same scenes
 * by definition.
 *
 * Every scene has an opaque, center-cropped base (no pixel depends on a window background) and is
 * placed for a view of exactly [W]x[H]. The gradient images are written into [dir] (the test's
 * cache directory); [deleteImages] removes them again.
 */
class WallpaperParityScenes(private val dir: File) {

    private val images = mutableListOf<File>()

    /** (1) Opaque base wider than the target, center-cropped; a translucent untransformed layer. */
    fun baseLandscape() = state(
        layer(image("parity_base_landscape", 400, 200, OPAQUE_A, OPAQUE_B)),
        layer(image("parity_top_landscape", 120, 120, TRANSLUCENT_A, TRANSLUCENT_B)),
    )

    /** (2) The same with a base taller than the target. */
    fun basePortrait() = state(
        layer(image("parity_base_portrait", 160, 600, OPAQUE_B, OPAQUE_A)),
        layer(image("parity_top_portrait", 200, 80, TRANSLUCENT_B, TRANSLUCENT_A)),
    )

    /** (3) Two transformed, partly transparent layers over the base. */
    fun twoTransformedTranslucent() = state(
        layer(image("parity_base_3", 120, 240, OPAQUE_A, OPAQUE_B)),
        layer(image("parity_mid_3", 200, 100, TRANSLUCENT_A, TRANSLUCENT_B), scale = 1.7f, x = -35f, y = 60f),
        layer(image("parity_top_3", 90, 90, TRANSLUCENT_B, CLEAR), scale = 2.3f, x = 70f, y = 140f),
    )

    /** (4) A scale captured at another sample size (2) than the render decode (1): compensated. */
    fun compensation() = state(
        layer(image("parity_base_4", 120, 240, OPAQUE_B, OPAQUE_A)),
        layer(image("parity_top_4", 150, 150, TRANSLUCENT_A, TRANSLUCENT_B), scale = 1.2f, x = 20f, y = 40f, captured = 2),
    )

    /** (5) One scale below the zoom range, one above: clamped at both ends (0.1 and 25). */
    fun clamping() = state(
        layer(image("parity_base_5", 120, 240, OPAQUE_A, OPAQUE_B)),
        layer(image("parity_low_5", 100, 100, TRANSLUCENT_B, TRANSLUCENT_A), scale = 0.001f, x = 50f, y = 50f),
        layer(image("parity_high_5", 60, 60, TRANSLUCENT_A, CLEAR), scale = 80f, x = -200f, y = -300f),
    )

    fun deleteImages() {
        images.forEach { it.delete() }
        images.clear()
    }

    private fun state(vararg layers: WallpaperLayerState) = WallpaperState(layers = layers.toList())

    private fun layer(file: File, scale: Float = 1f, x: Float = 0f, y: Float = 0f, captured: Int? = null) =
        WallpaperLayerState(
            imageUri = Uri.fromFile(file).toString(),
            scale = scale,
            translateX = x,
            translateY = y,
            captureSampleSize = captured,
        )

    private fun image(name: String, width: Int, height: Int, from: Int, to: Int): File {
        val bmp = createBitmap(width, height)
        Canvas(bmp).drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), from, to, Shader.TileMode.CLAMP)
            },
        )
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        images += file
        return file
    }

    companion object {
        const val W = 300
        const val H = 500

        private val OPAQUE_A = Color.rgb(250, 30, 30)
        private val OPAQUE_B = Color.rgb(30, 30, 250)
        private val TRANSLUCENT_A = Color.argb(200, 20, 220, 60)
        private val TRANSLUCENT_B = Color.argb(160, 255, 255, 255)
        private val CLEAR = Color.argb(0, 0, 0, 0)

        /**
         * The view's math, the reference of the bit-identical check: a detached [ZoomableImageView]
         * of [W]x[H], bound by the real [WallpaperViewBinder] with SOFTWARE decodes (the pre-Stufe-2
         * flatten's loader) and drawn with `view.draw(Canvas)` — measure, layout, bind,
         * `drawLayers`, exactly what the flatten computed before Stufe 2. [themedContext] must carry
         * an AppCompat theme (the view is an AppCompat widget). Call it where a View may be built
         * (Main on a device; the test thread under Robolectric).
         */
        suspend fun viewMath(themedContext: Context, state: WallpaperState): Bitmap {
            val view = ZoomableImageView(themedContext).apply {
                isEditMode = false
                measure(
                    View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, W, H)
            }
            WallpaperViewBinder { uri ->
                decodeBoundedWallpaperBitmap(preferSoftware = true) {
                    themedContext.contentResolver.openInputStream(uri)
                }
            }.bind(view, state)
            assertWithMessage("the view side must load every layer")
                .that(view.layerCount).isEqualTo(state.layerCount)
            return createBitmap(W, H).also { view.draw(Canvas(it)) }
        }

        /**
         * Guard against a trivially equal pair (e.g. two blank bitmaps): the scene must show the
         * gradients. Under Robolectric this is also what turns the test RED in the LEGACY graphics
         * mode, which paints no pixels — on purpose.
         */
        fun assertNotUniform(bitmap: Bitmap) {
            val sampled = buildSet {
                for (y in 0 until H step 25) for (x in 0 until W step 15) add(bitmap.getPixel(x, y))
            }
            assertWithMessage("the reference must not be uniform (real pixels were drawn)")
                .that(sampled.size).isAtLeast(16)
        }
    }
}
