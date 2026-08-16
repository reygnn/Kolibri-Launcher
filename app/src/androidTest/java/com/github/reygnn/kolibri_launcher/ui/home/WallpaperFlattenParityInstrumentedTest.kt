package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.media.ImageReader
import android.util.Log
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Phase-1 de-risking spike for WALLPAPER_DRAWER_HOME_REBUILD_SPEC Option D.
 *
 * Question the spec cannot answer on paper: how far does a **software** flatten
 * ([ZoomableImageView.composeToBitmap], Approach A) diverge from the **hardware**
 * render the live wallpaper actually shows? The delta only exists on a real
 * device (two rasterizers: the hardware-accelerated view canvas vs. a software
 * `Canvas`), so this EARNS androidTest (Rule 10) — Robolectric composites neither
 * faithfully, and blend modes are exactly where the two can differ.
 *
 * The hardware side (`view.draw` into a `RenderNode`, rendered via
 * [HardwareRenderer] to an [ImageReader] and read back) doubles as the Approach-B
 * offscreen-readback prototype: if the delta is too large for A, the machinery for
 * B is already here.
 *
 * Deliberately stacks a MULTIPLY layer at partial alpha — the worst case for
 * software-vs-hardware blend fidelity. Reports mean/max per-channel delta; the
 * assertion bound is generous because the point is the measured NUMBER, logged
 * under tag `WallpaperParity`, not a pass/fail gate yet.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperFlattenParityInstrumentedTest {

    private companion object {
        const val TAG = "WallpaperParity"
        const val W = 400
        const val H = 800
        // Generous: this is a measurement, not a gate. A real Approach-A decision
        // reads the logged mean/max, not this bound.
        const val MEAN_DELTA_BOUND = 24.0
    }

    @Test
    fun softwareFlattenMatchesHardwareRenderWithinTolerance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // View construction, layout, layer add, compose and hardware render must
        // all run on a thread with a Looper (the Main thread) — ZoomableImageView
        // creates gesture detectors (Handlers) in its constructor. The heavy
        // pixel comparison stays off the Main thread below.
        var software: Bitmap? = null
        var hardware: Bitmap? = null
        instrumentation.runOnMainSync {
            val view = ZoomableImageView(context).apply {
                isEditMode = false
                measure(
                    View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, W, H)
            }

            // Two distinct-content layers; the top one MULTIPLY at 0.6 alpha — the
            // stress case for blend fidelity.
            val base = solidGradient(W, H, Color.rgb(200, 120, 40))
            val top = solidGradient(W, H, Color.rgb(40, 120, 200))
            view.addLayer(base, centerCrop = true)
            view.addLayer(top, centerCrop = true, alpha = 0.6f, blendMode = BlendMode.MULTIPLY)

            // Approach A: software Canvas flatten.
            software = view.composeToBitmap(W, H)
            // Approach B / live-equivalent: record the view's onDraw into a
            // RenderNode and render it on hardware, then read the pixels back.
            hardware = renderViewOnHardware(view, W, H)
        }

        val softwareBmp = software ?: error("composeToBitmap returned null")
        val hardwareBmp = hardware
        Assume.assumeTrue(
            "hardware readback unavailable (software-rendered emulator?) — this " +
                "spike is device-only",
            hardwareBmp != null,
        )

        val (mean, max) = perChannelDelta(softwareBmp, hardwareBmp!!, W, H)
        Log.i(TAG, "software-vs-hardware flatten delta: mean=$mean max=$max (0..255)")

        assertTrue(
            "mean per-channel delta $mean exceeds $MEAN_DELTA_BOUND (max=$max) — " +
                "Approach A software flatten diverges from the hardware render; " +
                "see the logged number and consider Approach B.",
            mean < MEAN_DELTA_BOUND,
        )
    }

    /** A cheap non-uniform pattern so blend modes have varied input to act on. */
    private fun solidGradient(w: Int, h: Int, tint: Int): Bitmap {
        val bmp = createBitmap(w, h)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val r = (Color.red(tint) * (0.4f + 0.6f * t)).toInt().coerceIn(0, 255)
            val g = (Color.green(tint) * (0.4f + 0.6f * (1 - t))).toInt().coerceIn(0, 255)
            val b = (Color.blue(tint) * (0.5f + 0.5f * t)).toInt().coerceIn(0, 255)
            val c = Color.argb(255, r, g, b)
            for (x in 0 until w) bmp[x, y] = c
        }
        return bmp
    }

    /**
     * Records [view] into a [RenderNode] and renders it on a real hardware
     * pipeline via [HardwareRenderer] + [ImageReader], returning the read-back
     * pixels as a software `ARGB_8888` bitmap (or null if the platform cannot).
     */
    private fun renderViewOnHardware(view: View, w: Int, h: Int): Bitmap? {
        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val node = RenderNode("parity-content")
            node.setPosition(0, 0, w, h)
            val canvas = node.beginRecording(w, h)
            view.draw(canvas) // -> onDraw -> drawLayers on the hardware canvas
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            val image = reader.acquireNextImage() ?: return null
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedW = rowStride / pixelStride
                val padded = createBitmap(paddedW, h)
                padded.copyPixelsFromBuffer(buffer)
                return if (paddedW == w) padded else Bitmap.createBitmap(padded, 0, 0, w, h)
            } finally {
                image.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hardware render/readback failed", t)
            return null
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    /** Mean and max absolute per-channel (R,G,B) difference across all pixels. */
    private fun perChannelDelta(a: Bitmap, b: Bitmap, w: Int, h: Int): Pair<Double, Int> {
        var sum = 0L
        var max = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pa = a[x, y]
                val pb = b[x, y]
                val dr = abs(Color.red(pa) - Color.red(pb))
                val dg = abs(Color.green(pa) - Color.green(pb))
                val db = abs(Color.blue(pa) - Color.blue(pb))
                sum += dr + dg + db
                if (dr > max) max = dr
                if (dg > max) max = dg
                if (db > max) max = db
            }
        }
        return (sum.toDouble() / (w * h * 3)) to max
    }
}
