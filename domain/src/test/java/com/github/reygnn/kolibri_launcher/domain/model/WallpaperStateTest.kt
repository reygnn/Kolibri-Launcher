package com.github.reygnn.kolibri_launcher.domain.model

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Unit tests for [WallpaperState] representation helpers.
 *
 * The model is now layers-only: a single-image wallpaper is a one-element
 * [WallpaperState.layers] list (built via [WallpaperState.single]), a
 * composite is two-or-more layers ([WallpaperState.multiLayer]), and the
 * empty state is [WallpaperState.NONE]. The former flat `imageUri`/`scale`
 * fields and the `toSingleLayer`/`toMultiLayer`/`isMultiLayer` mode API were
 * removed, so their dedicated round-trip/collapse tests are gone; what
 * remains is the derived-getter surface expressed over the layer list.
 */
class WallpaperStateTest {

    // ---------------------------------------------------------------
    // single() factory
    // ---------------------------------------------------------------

    @Test
    fun `single builds a one-element layer list carrying the transform`() {
        val state = WallpaperState.single(
            uri = "file:///wallpapers/a.png",
            scale = 2.0f,
            translateX = 10f,
            translateY = -20f,
            captureSampleSize = 2,
        )

        assertEquals(1, state.layerCount)
        val layer = state.layers.single()
        assertEquals("file:///wallpapers/a.png", layer.imageUri)
        assertEquals(2.0f, layer.scale)
        assertEquals(10f, layer.translateX)
        assertEquals(-20f, layer.translateY)
        assertEquals(2, layer.captureSampleSize)
    }

    @Test
    fun `single defaults leave an untransformed layer`() {
        val state = WallpaperState.single("file:///a.png")

        val layer = state.layers.single()
        assertEquals(WallpaperState.DEFAULT_SCALE, layer.scale)
        assertEquals(0f, layer.translateX)
        assertEquals(0f, layer.translateY)
        assertFalse(layer.isTransformed)
    }

    // ---------------------------------------------------------------
    // layerCount
    // ---------------------------------------------------------------

    @Test
    fun `layerCount is zero for NONE, one for single, N for multi`() {
        assertEquals(0, WallpaperState.NONE.layerCount)
        assertEquals(1, WallpaperState.single("file:///a.png").layerCount)
        assertEquals(
            3,
            WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = "file:///a.png"),
                    WallpaperLayerState(imageUri = "file:///b.png"),
                    WallpaperLayerState(imageUri = "file:///c.png"),
                )
            ).layerCount,
        )
    }

    // ---------------------------------------------------------------
    // hasWallpaper
    // ---------------------------------------------------------------

    @Test
    fun `hasWallpaper is false for the empty state`() {
        assertFalse(WallpaperState.NONE.hasWallpaper)
    }

    @Test
    fun `hasWallpaper is true for a single image`() {
        assertTrue(WallpaperState.single("file:///a.png").hasWallpaper)
    }

    @Test
    fun `hasWallpaper is true when any layer carries an image`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = null),
                WallpaperLayerState(imageUri = "file:///b.png"),
            )
        )
        assertTrue(state.hasWallpaper)
    }

    @Test
    fun `hasWallpaper is false when no layer carries an image`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = null),
                WallpaperLayerState(imageUri = null),
            )
        )
        assertFalse(state.hasWallpaper)
    }

    // ---------------------------------------------------------------
    // isTransformed
    // ---------------------------------------------------------------

    @Test
    fun `isTransformed is false for the empty state`() {
        assertFalse(WallpaperState.NONE.isTransformed)
    }

    @Test
    fun `isTransformed follows the single layer transform`() {
        assertFalse(WallpaperState.single("file:///a.png").isTransformed)
        assertTrue(WallpaperState.single("file:///a.png", scale = 2f).isTransformed)
        assertTrue(WallpaperState.single("file:///a.png", translateX = 5f).isTransformed)
    }

    @Test
    fun `isTransformed is true when any layer is transformed`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "file:///a.png"),
                WallpaperLayerState(imageUri = "file:///b.png", scale = 3f),
            )
        )
        assertTrue(state.isTransformed)
    }

    // ---------------------------------------------------------------
    // referencedUris
    // ---------------------------------------------------------------

    @Test
    fun `referencedUris is empty for the empty state`() {
        assertTrue(WallpaperState.NONE.referencedUris.isEmpty())
    }

    @Test
    fun `referencedUris exposes the single image`() {
        assertEquals(
            setOf("file:///a.png"),
            WallpaperState.single("file:///a.png").referencedUris,
        )
    }

    @Test
    fun `referencedUris collects every non-null layer image`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "file:///a.png"),
                WallpaperLayerState(imageUri = null),
                WallpaperLayerState(imageUri = "file:///b.png"),
            )
        )
        assertEquals(setOf("file:///a.png", "file:///b.png"), state.referencedUris)
    }

    // ---------------------------------------------------------------
    // NONE identity
    // ---------------------------------------------------------------

    @Test
    fun `NONE is an empty layer list`() {
        assertTrue(WallpaperState.NONE.layers.isEmpty())
        assertSame(WallpaperState.NONE, WallpaperState.NONE)
    }

    // ---------------------------------------------------------------
    // Out-of-range mutation guards (RC edge-case audit B4)
    //
    // The layers-list mutators each guard their index and return `this`
    // unchanged on an out-of-range access; getLayer is null-safe. These
    // branches were only ever hit through mocked delegate tests, so the
    // real guards ran in no unit test. Pinned here on the actual model.
    // ---------------------------------------------------------------

    private fun twoLayerState(): WallpaperState = WallpaperState.multiLayer(
        listOf(
            WallpaperLayerState(imageUri = "file:///a.png"),
            WallpaperLayerState(imageUri = "file:///b.png"),
        ),
    )

    @Test
    fun `getLayer returns null for out-of-range and empty states`() {
        val state = twoLayerState()
        assertEquals("file:///a.png", state.getLayer(0)?.imageUri)
        assertNull(state.getLayer(99))
        assertNull(state.getLayer(-1))
        assertNull(WallpaperState.NONE.getLayer(0))
    }

    @Test
    fun `withRemovedLayer returns the same instance for an out-of-range index`() {
        val state = twoLayerState()
        assertSame(state, state.withRemovedLayer(-1))
        assertSame(state, state.withRemovedLayer(99))
    }

    @Test
    fun `withUpdatedLayer returns the same instance for an out-of-range index`() {
        val state = twoLayerState()
        assertSame(state, state.withUpdatedLayer(99) { it.copy(scale = 5f) })
    }

    @Test
    fun `withSwappedLayers returns the same instance when either index is out of range`() {
        val state = twoLayerState()
        assertSame(state, state.withSwappedLayers(0, 99))
        assertSame(state, state.withSwappedLayers(-1, 1))
    }

    @Test
    fun `withSwappedLayers with identical in-range indices preserves layer order`() {
        val state = twoLayerState()
        val result = state.withSwappedLayers(1, 1)
        // Both indices are valid, so the guard is not taken; swapping an index with
        // itself must leave the order intact.
        assertEquals(state.layers, result.layers)
        assertEquals("file:///a.png", result.getLayer(0)?.imageUri)
        assertEquals("file:///b.png", result.getLayer(1)?.imageUri)
    }
}
