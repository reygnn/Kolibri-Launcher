package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric guard for the single-image wallpaper path
 * ([WallpaperViewBinder]'s `applySingleLayer`). It must route through the bounded
 * [WallpaperViewBinder.BitmapLoader] (which downsamples) and set the bitmap via
 * `setImageBitmap` — NOT `ImageView.setImageURI`, which decodes at full
 * resolution and reintroduces the Canvas "too large bitmap" crash (#21). A revert
 * to `setImageURI` turns this red: the loader would never be called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperViewBinderSingleLayerTest {

    // Laid out so the binder takes the measured (synchronous) transform path —
    // the real drawer→home scenario. Without a layout the view is unmeasured
    // (width == 0), so the binder defers the transform + reveal to a View.post
    // that Robolectric's paused looper would never run, and the VISIBLE
    // assertion below could not be observed.
    private fun view() = ZoomableImageView(ApplicationProvider.getApplicationContext()).apply {
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, 1080, 1920)
    }

    @Test
    fun `single-image wallpaper loads through the bounded loader and sets the bitmap`() = runTest {
        var loaderCalls = 0
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val binder = WallpaperViewBinder { _ ->
            loaderCalls++
            bmp
        }
        val view = view()

        binder.bind(view, WallpaperState(imageUri = "file:///wallpaper.jpg"))

        assertEquals("routed through the bounded loader, not setImageURI", 1, loaderCalls)
        assertFalse("single-layer mode, not multi-layer", view.isMultiLayerMode)
        assertEquals("bitmap set on the view", bmp, (view.drawable as? BitmapDrawable)?.bitmap)
        assertEquals(View.VISIBLE, view.visibility)
        // Pins the actual fix, not just its side effect: on the measured path the
        // transform runs SYNCHRONOUSLY (centerCrop scales the 8x8 bitmap to cover
        // the 1080x1920 view → currentScale >> 1). A revert to the buggy
        // `visibility = VISIBLE; view.post { transform }` shape leaves this at
        // DEFAULT_SCALE (1.0) — the post never runs under Robolectric's paused
        // looper — and turns this assertion red. Without it the test would stay
        // green through a full revert (it only re-asserts VISIBLE, which the buggy
        // code also produced).
        assertTrue(
            "transform applied synchronously on the measured path — got scale ${view.currentScale}",
            view.currentScale > 1f
        )
    }

    @Test
    fun `single-image wallpaper whose load fails hides the view`() = runTest {
        val binder = WallpaperViewBinder { _ -> null } // decode failed / unreadable
        val view = view()

        binder.bind(view, WallpaperState(imageUri = "file:///broken.jpg"))

        assertEquals(View.GONE, view.visibility)
    }
}
