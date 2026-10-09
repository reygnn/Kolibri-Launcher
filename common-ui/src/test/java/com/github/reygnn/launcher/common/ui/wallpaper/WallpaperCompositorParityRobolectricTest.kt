package com.github.reygnn.launcher.common.ui.wallpaper

import android.app.Application
import android.view.ContextThemeWrapper
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperParityScenes.Companion.H
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperParityScenes.Companion.W
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The view-free flatten ([WallpaperFlattener] with [WallpaperLayerPlacement] +
 * [WallpaperLayerPainter]) is bit-identical (`sameAs`) to the view's math
 * ([WallpaperParityScenes.viewMath]) for the five parity scenes — on the host, at every `test`,
 * without a device (SPEC_NYX_REWRITE 3b/35). A guard: it fails as soon as plan, decode sample
 * size, target size, placement or drawing of the flatten and the view drift apart.
 *
 * Runs in Robolectric's NATIVE graphics mode (real host Skia, real PNG decodes) — set on this class
 * only. The default LEGACY mode paints no pixels, so `sameAs` would be trivially true there; the
 * uniform guard ([WallpaperParityScenes.assertNotUniform]) then turns this test RED instead of
 * falsely green. That is intended: losing the native mode must never pass silently.
 *
 * Not a replacement for `WallpaperCompositorParityInstrumentedTest`: host Skia is not the device's
 * Skia, and the comparison with the real live view needs a GPU and PixelCopy. The scenes come
 * from the shared [WallpaperParityScenes] (testFixtures), the same ones the device test uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperCompositorParityRobolectricTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val app: Application = RuntimeEnvironment.getApplication()
    private val scenes = WallpaperParityScenes(app.cacheDir)

    @After
    fun deleteImages() {
        scenes.deleteImages()
    }

    @Test fun baseLandscape_isBitIdenticalToTheViewMath() = assertBitIdentical(scenes.baseLandscape())
    @Test fun basePortrait_isBitIdenticalToTheViewMath() = assertBitIdentical(scenes.basePortrait())
    @Test fun twoTransformedTranslucent_isBitIdenticalToTheViewMath() = assertBitIdentical(scenes.twoTransformedTranslucent())
    @Test fun compensation_isBitIdenticalToTheViewMath() = assertBitIdentical(scenes.compensation())
    @Test fun clamping_isBitIdenticalToTheViewMath() = assertBitIdentical(scenes.clamping())

    private fun assertBitIdentical(state: WallpaperState) = runTest(mainDispatcherRule.testDispatcher) {
        val dispatcher = mainDispatcherRule.testDispatcher
        val composite = WallpaperFlattener(app, dispatcher, dispatcher).flatten(state, W, H)
        assertWithMessage("the flatten must produce a composite").that(composite).isNotNull()

        val viewMath = WallpaperParityScenes.viewMath(
            ContextThemeWrapper(app, androidx.appcompat.R.style.Theme_AppCompat),
            state,
        )
        WallpaperParityScenes.assertNotUniform(viewMath)
        assertWithMessage("the view-free flatten must be bit-identical to the view's math")
            .that(composite!!.sameAs(viewMath)).isTrue()
        composite.recycle()
        viewMath.recycle()
    }
}
