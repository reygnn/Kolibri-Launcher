package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Safety net for the onDraw matrix-reuse optimization.
 *
 * The per-frame draw path uses the allocation-free twins
 * [WallpaperLayer.buildMatrixInto] / [WallpaperLayer.getTransformedBoundsInto]
 * instead of the allocating [WallpaperLayer.buildMatrix] /
 * [WallpaperLayer.getTransformedBounds]. onDraw itself has no instrumented
 * coverage, so equivalence between each twin and its original is pinned here.
 *
 * Note: this is robust regardless of Robolectric's matrix-math fidelity —
 * both sides run the same Matrix operations, so the assertion compares the
 * twin against the original, not against hand-computed Android values.
 */
@RunWith(RobolectricTestRunner::class)
class WallpaperLayerMatrixTest {

    private fun layer(scale: Float, tx: Float, ty: Float): WallpaperLayer {
        val bmp = Bitmap.createBitmap(40, 25, Bitmap.Config.ARGB_8888)
        return WallpaperLayer(
            bitmap = bmp,
            intrinsicWidth = bmp.width,
            intrinsicHeight = bmp.height,
            scale = scale,
            translateX = tx,
            translateY = ty,
        )
    }

    private val cases = listOf(
        Triple(1f, 0f, 0f),
        Triple(2.5f, 10f, -5f),
        Triple(0.3f, -120f, 33f),
        Triple(40f, 0f, 250f),
    )

    @Test
    fun `buildMatrixInto matches buildMatrix for all cases`() {
        for ((scale, tx, ty) in cases) {
            val layer = layer(scale, tx, ty)
            val expected = FloatArray(9).also { layer.buildMatrix().getValues(it) }
            val out = Matrix()
            layer.buildMatrixInto(out)
            val actual = FloatArray(9).also { out.getValues(it) }
            assertArrayEquals("scale=$scale tx=$tx ty=$ty", expected, actual, 0f)
        }
    }

    @Test
    fun `buildMatrixInto ignores prior matrix contents`() {
        val layer = layer(2f, 7f, 9f)
        val expected = FloatArray(9).also { layer.buildMatrix().getValues(it) }
        // Pre-dirty the output matrix to prove reset() inside is doing its job.
        val out = Matrix().apply { postScale(99f, 99f); postTranslate(500f, 500f) }
        layer.buildMatrixInto(out)
        val actual = FloatArray(9).also { out.getValues(it) }
        assertArrayEquals(expected, actual, 0f)
    }

    @Test
    fun `getTransformedBoundsInto matches getTransformedBounds for all cases`() {
        for ((scale, tx, ty) in cases) {
            val layer = layer(scale, tx, ty)
            val expected = layer.getTransformedBounds()!!
            val out = RectF()
            assertTrue(layer.getTransformedBoundsInto(out, Matrix()))
            assertEquals("left", expected.left, out.left, 0f)
            assertEquals("top", expected.top, out.top, 0f)
            assertEquals("right", expected.right, out.right, 0f)
            assertEquals("bottom", expected.bottom, out.bottom, 0f)
        }
    }

    @Test
    fun `getTransformedBoundsInto returns false without a bitmap`() {
        val layer = WallpaperLayer(bitmap = null)
        val out = RectF(1f, 2f, 3f, 4f)
        assertFalse(layer.getTransformedBoundsInto(out, Matrix()))
    }
}
