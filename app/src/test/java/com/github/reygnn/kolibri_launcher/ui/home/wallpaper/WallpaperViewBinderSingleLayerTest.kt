package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@RunWith(RobolectricTestRunner::class)
class WallpaperViewBinderSingleLayerTest {

    private fun view() = ZoomableImageView(ApplicationProvider.getApplicationContext())

    @Test
    fun `single-image wallpaper loads through the bounded loader and sets the bitmap`() {
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
    }

    @Test
    fun `single-image wallpaper whose load fails hides the view`() {
        val binder = WallpaperViewBinder { _ -> null } // decode failed / unreadable
        val view = view()

        binder.bind(view, WallpaperState(imageUri = "file:///broken.jpg"))

        assertEquals(View.GONE, view.visibility)
    }
}
