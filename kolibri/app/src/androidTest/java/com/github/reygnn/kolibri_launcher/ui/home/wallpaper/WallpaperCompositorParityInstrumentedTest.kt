package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattener
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperViewBinder
import com.github.reygnn.launcher.common.ui.wallpaper.ZoomableImageView
import com.github.reygnn.launcher.common.ui.wallpaper.decodeBoundedWallpaperBitmap
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Stufe 2 (SPEC_NYX_REWRITE): the view-free flatten ([WallpaperFlattener] with
 * `WallpaperLayerPlacement` + `WallpaperLayerPainter`) against the multi-layer view, for the same
 * five scenes:
 *
 * - **bit-identical to the view's math** — a detached [ZoomableImageView], bound by the real
 *   [WallpaperViewBinder] with SOFTWARE decodes and drawn with `view.draw(Canvas)`, is exactly
 *   what the flatten computed before Stufe 2 (measure, layout, bind, `drawLayers`). `sameAs`.
 *   Introduced while the view still had its own math (3b/33), it proved that the new placement
 *   and drawing change no pixel; since the view calls the same placement and painter (3b/34) it
 *   guards the rest of the path — the same plan, decode sample sizes and view size.
 * - **the real live view on screen** — a [ZoomableImageView] in a window, bound like Kolibri's
 *   live view (HARDWARE decodes, GPU), read back with [PixelCopy] after a committed frame. Two
 *   rasterizers (Skia CPU vs. GPU), so a tolerance: per channel max ≤ 2 and mean ≤ 0.5 (the A36
 *   reference, NORMAL mean 0.34 / max 2). On a failure the message carries mean, max and the
 *   number of pixels off by more than 2 — the tolerance is NOT to be widened without a decision.
 *
 * Every scene has an opaque, center-cropped base, so no pixel depends on the window background.
 * Device-only (Rule 10): real decodes, a real software `Canvas` and the real GPU render path.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WallpaperCompositorParityInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val images = mutableListOf<File>()

    @After
    fun deleteImages() {
        images.forEach { it.delete() }
    }

    // ---- scenes (1)–(5) ----

    /** (1) Opaque base wider than the target, center-cropped; a translucent untransformed layer. */
    private fun baseLandscape() = state(
        layer(image("parity_base_landscape", 400, 200, OPAQUE_A, OPAQUE_B)),
        layer(image("parity_top_landscape", 120, 120, TRANSLUCENT_A, TRANSLUCENT_B)),
    )

    /** (2) The same with a base taller than the target. */
    private fun basePortrait() = state(
        layer(image("parity_base_portrait", 160, 600, OPAQUE_B, OPAQUE_A)),
        layer(image("parity_top_portrait", 200, 80, TRANSLUCENT_B, TRANSLUCENT_A)),
    )

    /** (3) Two transformed, partly transparent layers over the base. */
    private fun twoTransformedTranslucent() = state(
        layer(image("parity_base_3", 120, 240, OPAQUE_A, OPAQUE_B)),
        layer(image("parity_mid_3", 200, 100, TRANSLUCENT_A, TRANSLUCENT_B), scale = 1.7f, x = -35f, y = 60f),
        layer(image("parity_top_3", 90, 90, TRANSLUCENT_B, CLEAR), scale = 2.3f, x = 70f, y = 140f),
    )

    /** (4) A scale captured at another sample size (2) than the render decode (1): compensated. */
    private fun compensation() = state(
        layer(image("parity_base_4", 120, 240, OPAQUE_B, OPAQUE_A)),
        layer(image("parity_top_4", 150, 150, TRANSLUCENT_A, TRANSLUCENT_B), scale = 1.2f, x = 20f, y = 40f, captured = 2),
    )

    /** (5) One scale below the zoom range, one above: clamped at both ends. */
    private fun clamping() = state(
        layer(image("parity_base_5", 120, 240, OPAQUE_A, OPAQUE_B)),
        layer(image("parity_low_5", 100, 100, TRANSLUCENT_B, TRANSLUCENT_A), scale = 0.001f, x = 50f, y = 50f),
        layer(image("parity_high_5", 60, 60, TRANSLUCENT_A, CLEAR), scale = 80f, x = -200f, y = -300f),
    )

    // ---- bit-identical to the view's math ----

    @Test fun baseLandscape_isBitIdenticalToTheViewMath() = assertBitIdentical(baseLandscape())
    @Test fun basePortrait_isBitIdenticalToTheViewMath() = assertBitIdentical(basePortrait())
    @Test fun twoTransformedTranslucent_isBitIdenticalToTheViewMath() = assertBitIdentical(twoTransformedTranslucent())
    @Test fun compensation_isBitIdenticalToTheViewMath() = assertBitIdentical(compensation())
    @Test fun clamping_isBitIdenticalToTheViewMath() = assertBitIdentical(clamping())

    // ---- the real live view on screen ----

    @Test fun baseLandscape_matchesTheLiveView() = assertMatchesLiveView(baseLandscape())
    @Test fun basePortrait_matchesTheLiveView() = assertMatchesLiveView(basePortrait())
    @Test fun twoTransformedTranslucent_matchesTheLiveView() = assertMatchesLiveView(twoTransformedTranslucent())
    @Test fun compensation_matchesTheLiveView() = assertMatchesLiveView(compensation())
    @Test fun clamping_matchesTheLiveView() = assertMatchesLiveView(clamping())

    // ---- checks ----

    private fun assertBitIdentical(state: WallpaperState) = runBlocking {
        val composite = flatten(state)
        val viewMath = withContext(Dispatchers.Main) {
            val view = ZoomableImageView(ContextThemeWrapper(context, R.style.AppTheme)).apply {
                isEditMode = false
                measure(
                    View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, W, H)
            }
            binder(software = true).bind(view, state)
            assertThat(view.layerCount).isEqualTo(state.layerCount)
            createBitmap(W, H).also { view.draw(Canvas(it)) }
        }
        assertNotUniform(viewMath)
        assertWithMessage("the view-free flatten must be bit-identical to the view's math")
            .that(composite.sameAs(viewMath)).isTrue()
        composite.recycle()
        viewMath.recycle()
    }

    private fun assertMatchesLiveView(state: WallpaperState) = runBlocking {
        val composite = flatten(state)
        ActivityScenario.launch<HiltTestActivity>(Intent(context, HiltTestActivity::class.java)).use { scenario ->
            lateinit var activity: HiltTestActivity
            scenario.onActivity { activity = it }
            val live = withTimeout(TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    val view = ZoomableImageView(activity).apply { isEditMode = false }
                    val root = FrameLayout(activity)
                    root.addView(view, FrameLayout.LayoutParams(W, H, Gravity.CENTER))
                    activity.setContentView(root)
                    suspendCancellableCoroutine { cont -> view.doOnLayout { cont.resume(Unit) } }

                    binder(software = false).bind(view, state)
                    assertThat(view.layerCount).isEqualTo(state.layerCount)
                    assertThat(view.visibility).isEqualTo(View.VISIBLE)
                    // A confirmed frame with the bound state before reading the screen.
                    awaitFrameCommit(view)
                    awaitFrameCommit(view)

                    assertPreconditions(activity, view)
                    val location = IntArray(2).also { view.getLocationInWindow(it) }
                    val dest = createBitmap(W, H)
                    val result = suspendCancellableCoroutine { cont ->
                        PixelCopy.request(
                            activity.window,
                            Rect(location[0], location[1], location[0] + W, location[1] + H),
                            dest,
                            { copyResult -> cont.resume(copyResult) },
                            Handler(Looper.getMainLooper()),
                        )
                    }
                    assertWithMessage("PixelCopy result").that(result).isEqualTo(PixelCopy.SUCCESS)
                    dest
                }
            }
            assertNotUniform(live)
            val d = delta(live, composite)
            assertWithMessage(
                "live view vs. flatten: mean %s, max %s, pixels off by more than 2: %s (limits: max ≤ 2, mean ≤ 0.5)",
                "%.3f".format(d.mean), d.max, d.pixelsOver2,
            ).that(d.max <= 2 && d.mean <= 0.5).isTrue()
            live.recycle()
        }
        composite.recycle()
    }

    /** The conditions the senior set for reading the screen, checked rather than assumed. */
    private fun assertPreconditions(activity: HiltTestActivity, view: View) {
        assertWithMessage("animations must be off (the test runner disables them)")
            .that(ValueAnimator.areAnimatorsEnabled()).isFalse()
        assertWithMessage("standard colour mode (sRGB)")
            .that(activity.window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_DEFAULT)
        assertWithMessage("the live view must render on the GPU").that(view.isHardwareAccelerated).isTrue()
        assertThat(view.width).isEqualTo(W)
        assertThat(view.height).isEqualTo(H)
        val insets = ViewCompat.getRootWindowInsets(view)!!
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val decor = activity.window.decorView
        val location = IntArray(2).also { view.getLocationInWindow(it) }
        assertWithMessage("the view must lie inside the content area, not under a system bar")
            .that(
                location[0] >= insets.left && location[1] >= insets.top &&
                    location[0] + W <= decor.width - insets.right &&
                    location[1] + H <= decor.height - insets.bottom,
            ).isTrue()
    }

    private suspend fun awaitFrameCommit(view: View) = suspendCancellableCoroutine { cont ->
        view.viewTreeObserver.registerFrameCommitCallback { cont.resume(Unit) }
        view.invalidate()
    }

    // ---- helpers ----

    private suspend fun flatten(state: WallpaperState): Bitmap {
        val composite = WallpaperFlattener(context, Dispatchers.IO, Dispatchers.Default).flatten(state, W, H)
        assertWithMessage("the flatten must produce a composite").that(composite).isNotNull()
        return composite!!
    }

    /** The live binder as Kolibri's MainActivity builds it (HARDWARE), or the pre-Stufe-2 flatten's (SOFTWARE). */
    private fun binder(software: Boolean) = WallpaperViewBinder { uri ->
        withContext(Dispatchers.IO) {
            decodeBoundedWallpaperBitmap(preferSoftware = software) { context.contentResolver.openInputStream(uri) }
        }
    }

    private class Delta(val mean: Double, val max: Int, val pixelsOver2: Int)

    private fun delta(a: Bitmap, b: Bitmap): Delta {
        val pa = IntArray(W * H).also { a.getPixels(it, 0, W, 0, 0, W, H) }
        val pb = IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H) }
        var sum = 0L
        var max = 0
        var over = 0
        for (i in pa.indices) {
            var pixelMax = 0
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val d = abs(((pa[i] ushr shift) and 0xFF) - ((pb[i] ushr shift) and 0xFF))
                sum += d
                if (d > pixelMax) pixelMax = d
            }
            if (pixelMax > max) max = pixelMax
            if (pixelMax > 2) over++
        }
        return Delta(mean = sum.toDouble() / (pa.size * 4), max = max, pixelsOver2 = over)
    }

    /** Guard against a trivially equal pair (e.g. two blank bitmaps): the scene must show the gradients. */
    private fun assertNotUniform(bitmap: Bitmap) {
        val sampled = buildSet {
            for (y in 0 until H step 25) for (x in 0 until W step 15) add(bitmap.getPixel(x, y))
        }
        assertWithMessage("the reference must not be uniform").that(sampled.size).isAtLeast(16)
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
        val file = File(context.cacheDir, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        images += file
        return file
    }

    private companion object {
        const val W = 300
        const val H = 500
        const val TIMEOUT_MS = 10_000L
        val OPAQUE_A = Color.rgb(250, 30, 30)
        val OPAQUE_B = Color.rgb(30, 30, 250)
        val TRANSLUCENT_A = Color.argb(200, 20, 220, 60)
        val TRANSLUCENT_B = Color.argb(160, 255, 255, 255)
        val CLEAR = Color.argb(0, 0, 0, 0)
    }
}
