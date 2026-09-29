package com.github.reygnn.launcher.core.wallpaper

import com.google.common.truth.Truth.assertThat
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

        assertThat(state.layerCount).isEqualTo(1)
        val layer = state.layers.single()
        assertThat(layer.imageUri).isEqualTo("file:///wallpapers/a.png")
        assertThat(layer.scale).isEqualTo(2.0f)
        assertThat(layer.translateX).isEqualTo(10f)
        assertThat(layer.translateY).isEqualTo(-20f)
        assertThat(layer.captureSampleSize).isEqualTo(2)
    }

    @Test
    fun `single defaults leave an untransformed layer`() {
        val state = WallpaperState.single("file:///a.png")

        val layer = state.layers.single()
        assertThat(layer.scale).isEqualTo(WallpaperState.DEFAULT_SCALE)
        assertThat(layer.translateX).isEqualTo(0f)
        assertThat(layer.translateY).isEqualTo(0f)
        assertThat(layer.isTransformed).isFalse()
    }

    // ---------------------------------------------------------------
    // layerCount
    // ---------------------------------------------------------------

    @Test
    fun `layerCount is zero for NONE, one for single, N for multi`() {
        assertThat(WallpaperState.NONE.layerCount).isEqualTo(0)
        assertThat(WallpaperState.single("file:///a.png").layerCount).isEqualTo(1)
        assertThat(WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = "file:///a.png"),
                    WallpaperLayerState(imageUri = "file:///b.png"),
                    WallpaperLayerState(imageUri = "file:///c.png"),
                )
            ).layerCount).isEqualTo(3)
    }

    // ---------------------------------------------------------------
    // hasWallpaper
    // ---------------------------------------------------------------

    @Test
    fun `hasWallpaper is false for the empty state`() {
        assertThat(WallpaperState.NONE.hasWallpaper).isFalse()
    }

    @Test
    fun `hasWallpaper is true for a single image`() {
        assertThat(WallpaperState.single("file:///a.png").hasWallpaper).isTrue()
    }

    @Test
    fun `hasWallpaper is true when any layer carries an image`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = null),
                WallpaperLayerState(imageUri = "file:///b.png"),
            )
        )
        assertThat(state.hasWallpaper).isTrue()
    }

    @Test
    fun `hasWallpaper is false when no layer carries an image`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = null),
                WallpaperLayerState(imageUri = null),
            )
        )
        assertThat(state.hasWallpaper).isFalse()
    }

    // ---------------------------------------------------------------
    // isTransformed
    // ---------------------------------------------------------------

    @Test
    fun `isTransformed is false for the empty state`() {
        assertThat(WallpaperState.NONE.isTransformed).isFalse()
    }

    @Test
    fun `isTransformed follows the single layer transform`() {
        assertThat(WallpaperState.single("file:///a.png").isTransformed).isFalse()
        assertThat(WallpaperState.single("file:///a.png", scale = 2f).isTransformed).isTrue()
        assertThat(WallpaperState.single("file:///a.png", translateX = 5f).isTransformed).isTrue()
    }

    @Test
    fun `isTransformed is true when any layer is transformed`() {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "file:///a.png"),
                WallpaperLayerState(imageUri = "file:///b.png", scale = 3f),
            )
        )
        assertThat(state.isTransformed).isTrue()
    }

    // ---------------------------------------------------------------
    // referencedUris
    // ---------------------------------------------------------------

    @Test
    fun `referencedUris is empty for the empty state`() {
        assertThat(WallpaperState.NONE.referencedUris.isEmpty()).isTrue()
    }

    @Test
    fun `referencedUris exposes the single image`() {
        assertThat(WallpaperState.single("file:///a.png").referencedUris).isEqualTo(setOf("file:///a.png"))
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
        assertThat(state.referencedUris).isEqualTo(setOf("file:///a.png", "file:///b.png"))
    }

    // ---------------------------------------------------------------
    // NONE identity
    // ---------------------------------------------------------------

    @Test
    fun `NONE is an empty layer list`() {
        assertThat(WallpaperState.NONE.layers.isEmpty()).isTrue()
        assertThat(WallpaperState.NONE).isSameInstanceAs(WallpaperState.NONE)
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
        assertThat(state.getLayer(0)?.imageUri).isEqualTo("file:///a.png")
        assertThat(state.getLayer(99)).isNull()
        assertThat(state.getLayer(-1)).isNull()
        assertThat(WallpaperState.NONE.getLayer(0)).isNull()
    }

    @Test
    fun `withRemovedLayer returns the same instance for an out-of-range index`() {
        val state = twoLayerState()
        assertThat(state.withRemovedLayer(-1)).isSameInstanceAs(state)
        assertThat(state.withRemovedLayer(99)).isSameInstanceAs(state)
    }

    @Test
    fun `withUpdatedLayer returns the same instance for an out-of-range index`() {
        val state = twoLayerState()
        assertThat(state.withUpdatedLayer(99) { it.copy(scale = 5f) }).isSameInstanceAs(state)
    }

    @Test
    fun `withSwappedLayers returns the same instance when either index is out of range`() {
        val state = twoLayerState()
        assertThat(state.withSwappedLayers(0, 99)).isSameInstanceAs(state)
        assertThat(state.withSwappedLayers(-1, 1)).isSameInstanceAs(state)
    }

    @Test
    fun `withSwappedLayers with identical in-range indices preserves layer order`() {
        val state = twoLayerState()
        val result = state.withSwappedLayers(1, 1)
        // Both indices are valid, so the guard is not taken; swapping an index with
        // itself must leave the order intact.
        assertThat(result.layers).isEqualTo(state.layers)
        assertThat(result.getLayer(0)?.imageUri).isEqualTo("file:///a.png")
        assertThat(result.getLayer(1)?.imageUri).isEqualTo("file:///b.png")
    }
}
