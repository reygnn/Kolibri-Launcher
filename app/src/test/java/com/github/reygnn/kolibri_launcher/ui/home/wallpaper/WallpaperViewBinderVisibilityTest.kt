package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric guards for the wallpaper rebuild-flicker fix (A+B) in
 * [WallpaperViewBinder], covering the two paths the single-layer happy-path test
 * does not exercise:
 *
 * - The DEFERRED (unmeasured) path — [WallpaperViewBinder]'s `runWhenMeasured`
 *   falls back to `View.post` when the view has no size yet. Option A must keep
 *   the view hidden and Option B must NOT apply the transform synchronously, so
 *   the user gets a blank frame rather than the identity-matrix (wrong) frame.
 * - The full-rebuild reveal branch (applyUpdates' `finally`): VISIBLE when at
 *   least one layer survived, GONE when every layer failed to load. A regression
 *   dropping the `layerCount > 0` check passes every other test but this one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperViewBinderVisibilityTest {

    /** Laid out (1080x1920) → the binder takes the measured, synchronous path. */
    private fun laidOutView() =
        ZoomableImageView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1080, 1920)
        }

    /** Never laid out (width == 0) → exercises runWhenMeasured's deferred branch. */
    private fun unmeasuredView() =
        ZoomableImageView(ApplicationProvider.getApplicationContext())

    private fun bitmap() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun layer(id: String) = WallpaperLayerState(
        id = id,
        imageUri = "file:///data/$id.jpg",
        label = null,
    )

    // ---- Deferred path: hide, do not show a wrong frame (Option A + B) ----

    @Test
    fun `single-layer on an unmeasured view stays hidden with the transform deferred`() = runTest {
        val binder = WallpaperViewBinder { _ -> DecodedWallpaperBitmap(bitmap(), 1, 8, 8) }
        val view = unmeasuredView()

        binder.bind(view, WallpaperState(imageUri = "file:///wallpaper.jpg"))

        // Option A: not yet revealed — the deferred frame is blank, never the
        // identity-matrix (top-left, native 1:1) wrong frame.
        assertEquals(
            "hidden until the transform lands, not showing identity",
            View.INVISIBLE,
            view.visibility
        )
        // Option B: on an unmeasured view the transform is deferred to the post,
        // NOT applied synchronously — currentScale is still DEFAULT_SCALE (1.0).
        assertEquals(
            "transform deferred on the unmeasured path, not applied synchronously",
            1.0f,
            view.currentScale,
            0.0001f
        )
    }

    // ---- Full-rebuild reveal branch (applyUpdates finally) ----

    @Test
    fun `full rebuild with surviving layers reveals the view`() = runTest {
        val binder = WallpaperViewBinder { _ -> DecodedWallpaperBitmap(bitmap(), 1, 8, 8) }
        val view = laidOutView()

        binder.bind(view, WallpaperState.multiLayer(listOf(layer("L0"), layer("L1"))))

        assertEquals("both layers added", 2, view.layerCount)
        assertEquals("revealed after transforms (layerCount > 0)", View.VISIBLE, view.visibility)
    }

    @Test
    fun `full rebuild where every layer fails to load hides the view`() = runTest {
        val binder = WallpaperViewBinder { _ -> null } // every decode fails
        val view = laidOutView()

        binder.bind(view, WallpaperState.multiLayer(listOf(layer("L0"), layer("L1"))))

        assertEquals("no layers survived", 0, view.layerCount)
        assertEquals("GONE when a rebuild produced zero layers", View.GONE, view.visibility)
    }
}
