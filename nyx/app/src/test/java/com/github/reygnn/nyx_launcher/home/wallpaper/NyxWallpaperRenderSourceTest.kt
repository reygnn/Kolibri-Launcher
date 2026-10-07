package com.github.reygnn.nyx_launcher.home.wallpaper

import android.graphics.Bitmap
import android.net.Uri
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [NyxWallpaperRenderSource] (3b-6, E3): the read side of the home. In display mode a warmed
 * composite is shown as one image; the editor never gets a composite target (R1); every non-composite
 * target still goes through the layer cache, as before 3b-6.
 */
@RunWith(RobolectricTestRunner::class)
class NyxWallpaperRenderSourceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val twoLayers = WallpaperState.multiLayer(
        listOf(WallpaperLayerState(id = "a", imageUri = "file:///w/a"), WallpaperLayerState(id = "b", imageUri = "file:///w/b")),
    )
    private val compositeKey = "composite://key-1"
    private val compositeBitmap = DecodedWallpaperBitmap(bitmap = mockk<Bitmap>(relaxed = true), sampleSize = 1, originalWidth = 10, originalHeight = 20)
    private val composite = mockk<WallpaperComposite> {
        every { cachedKeyFor(any(), any(), any()) } returns compositeKey
        every { cachedBitmap(compositeKey) } returns compositeBitmap
    }
    private val layerCache = WallpaperLayerBitmapCache()
    private var decodes = 0
    private val decoded = DecodedWallpaperBitmap(bitmap = mockk<Bitmap>(relaxed = true), sampleSize = 1, originalWidth = 10, originalHeight = 20)
    private val source = NyxWallpaperRenderSource(composite, layerCache, mainDispatcherRule.testDispatcher) { decodes++; decoded }

    @Test
    fun a_composite_target_comes_from_the_composite() = runTest(mainDispatcherRule.testDispatcher) {
        val target = source.displayTargetFor(twoLayers, isEditMode = false, widthPx = 1080, heightPx = 2340)

        assertThat(target.layers.single().imageUri).isEqualTo(compositeKey)
        assertThat(source.load(Uri.parse(compositeKey))).isSameInstanceAs(compositeBitmap)
        assertThat(decodes).isEqualTo(0) // neither the decoder nor the layer cache is asked
    }

    @Test
    fun a_multi_layer_target_still_goes_through_the_layer_cache() = runTest(mainDispatcherRule.testDispatcher) {
        val uri = Uri.parse("file:///w/a")

        val first = source.load(uri)
        val second = source.load(uri)

        assertThat(first).isSameInstanceAs(decoded)
        assertThat(second).isSameInstanceAs(decoded)
        assertThat(decodes).isEqualTo(1) // the second load comes from the layer cache
    }

    @Test
    fun the_editor_never_gets_a_composite_target() = runTest(mainDispatcherRule.testDispatcher) {
        // R1: even with a warmed composite, the editor works on the real layers.
        val target = source.displayTargetFor(twoLayers, isEditMode = true, widthPx = 1080, heightPx = 2340)

        assertThat(target).isSameInstanceAs(twoLayers)
    }
}
