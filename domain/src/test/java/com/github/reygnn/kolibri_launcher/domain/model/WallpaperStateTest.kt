package com.github.reygnn.kolibri_launcher.domain.model

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Unit tests for [WallpaperState] representation helpers — focused on the
 * AUDIT-20 F13 collapse ([WallpaperState.toSingleLayer]) and its round-trip
 * with [WallpaperState.toMultiLayer].
 */
class WallpaperStateTest {

    // ---------------------------------------------------------------
    // toSingleLayer — the happy path: one plain layer collapses
    // ---------------------------------------------------------------

    @Test
    fun `toSingleLayer collapses a single plain layer into the single-layer representation`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(
                    imageUri = "file:///wallpapers/a.png",
                    scale = 2.0f,
                    translateX = 10f,
                    translateY = -20f,
                    captureSampleSize = 2,
                    label = "Layer 1",
                )
            )
        )

        val collapsed = state.toSingleLayer()

        assertFalse(collapsed.isMultiLayer, "collapsed state must be single-layer")
        assertTrue(collapsed.layers.isEmpty(), "layers must be cleared")
        assertEquals("file:///wallpapers/a.png", collapsed.imageUri)
        assertEquals(2.0f, collapsed.scale)
        assertEquals(10f, collapsed.translateX)
        assertEquals(-20f, collapsed.translateY)
        assertEquals(2, collapsed.captureSampleSize)
        assertTrue(collapsed.hasWallpaper)
    }

    // ---------------------------------------------------------------
    // toSingleLayer — unconditional for a one-layer list
    // ---------------------------------------------------------------

    @Test
    fun `toSingleLayer collapses a single layer regardless of its dead per-layer props`() {
        // alpha/blend/isVisible are UI-less by design and slated for retirement;
        // no state carries non-default values in practice, so the collapse is
        // unconditional and drops them (AUDIT-20 F13/F14).
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(
                    imageUri = "file:///a.png",
                    alpha = 0.5f,
                    blendModeName = "MULTIPLY",
                    isVisible = false,
                )
            )
        )

        val collapsed = state.toSingleLayer()

        assertFalse(collapsed.isMultiLayer, "a one-layer list always collapses")
        assertEquals("file:///a.png", collapsed.imageUri)
    }

    // ---------------------------------------------------------------
    // toSingleLayer — no-ops: wrong layer count / already single
    // ---------------------------------------------------------------

    @Test
    fun `toSingleLayer is a no-op for two-plus layers`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "file:///a.png"),
                WallpaperLayerState(imageUri = "file:///b.png"),
            )
        )

        assertSame(state, state.toSingleLayer())
    }

    @Test
    fun `toSingleLayer is a no-op for an already single-layer state`() {
        val state = WallpaperState(imageUri = "file:///a.png", scale = 1.5f)

        assertSame(state, state.toSingleLayer())
    }

    @Test
    fun `toSingleLayer is a no-op for the empty state`() {
        assertSame(WallpaperState.NONE, WallpaperState.NONE.toSingleLayer())
    }

    // ---------------------------------------------------------------
    // toMultiLayer — the forward direction (previously untested)
    // ---------------------------------------------------------------

    @Test
    fun `toMultiLayer converts a single-layer state into one labelled layer`() {
        val single = WallpaperState(
            imageUri = "file:///wallpapers/a.png",
            scale = 2.0f,
            translateX = 5f,
            translateY = -3f,
            captureSampleSize = 2,
        )

        val multi = single.toMultiLayer()

        assertTrue(multi.isMultiLayer)
        assertEquals(1, multi.layerCount)
        val layer = multi.layers.single()
        assertEquals("file:///wallpapers/a.png", layer.imageUri)
        assertEquals(2.0f, layer.scale)
        assertEquals(5f, layer.translateX)
        assertEquals(-3f, layer.translateY)
        assertEquals(2, layer.captureSampleSize)
        assertEquals("Layer 1", layer.label)
    }

    @Test
    fun `toMultiLayer resets the single-layer fields to leave no shadow state`() {
        val single = WallpaperState(imageUri = "file:///a.png", scale = 2f, translateX = 5f)

        val multi = single.toMultiLayer()

        assertNull(multi.imageUri)
        assertEquals(WallpaperState.DEFAULT_SCALE, multi.scale)
        assertEquals(0f, multi.translateX)
        assertEquals(0f, multi.translateY)
        assertNull(multi.captureSampleSize)
        // Only the layer branch references the image now.
        assertEquals(setOf("file:///a.png"), multi.referencedUris)
    }

    @Test
    fun `toMultiLayer is a no-op for an already multi-layer state`() {
        val state = WallpaperState.multiLayer(listOf(WallpaperLayerState(imageUri = "file:///a.png")))

        assertSame(state, state.toMultiLayer())
    }

    @Test
    fun `toMultiLayer is a no-op for the empty state`() {
        assertSame(WallpaperState.NONE, WallpaperState.NONE.toMultiLayer())
    }

    // ---------------------------------------------------------------
    // Round-trip with toMultiLayer
    // ---------------------------------------------------------------

    @Test
    fun `toMultiLayer then toSingleLayer round-trips a single-layer state`() {
        val original = WallpaperState(
            imageUri = "file:///wallpapers/a.png",
            scale = 3.0f,
            translateX = 42f,
            translateY = 7f,
            captureSampleSize = 4,
        )

        val roundTripped = original.toMultiLayer().toSingleLayer()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `id and label are dropped by the collapse`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(
                    id = "layer_custom_id",
                    imageUri = "file:///a.png",
                    label = "Base",
                )
            )
        )

        val collapsed = state.toSingleLayer()

        // The single-layer representation has no id/label home; the only proof is
        // that the image round-trips while the layer (and thus its id/label) is gone.
        assertTrue(collapsed.layers.isEmpty())
        assertEquals("file:///a.png", collapsed.imageUri)
    }

    // ---------------------------------------------------------------
    // Invariant guard: single-layer fields default while multi-layer
    // ---------------------------------------------------------------

    @Test
    fun `collapsed state leaves no shadow multi-layer field`() {
        val state = WallpaperState.multiLayer(
            listOf(WallpaperLayerState(imageUri = "file:///a.png", scale = 2f))
        )

        val collapsed = state.toSingleLayer()

        assertTrue(collapsed.layers.isEmpty())
        // referencedUris must still see the image via the single-layer branch.
        assertEquals(setOf("file:///a.png"), collapsed.referencedUris)
        assertNull(collapsed.layers.firstOrNull())
    }
}
