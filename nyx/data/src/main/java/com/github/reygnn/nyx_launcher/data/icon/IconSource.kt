package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.IconStyle

/**
 * Resolves + rasterizes an icon (the Android/system half of the loader). Split
 * out so the caching layer is testable with a fake. [style] selects the rendering
 * (colour / monochrome / grayscale). Called on an IO dispatcher.
 */
interface IconSource {
    suspend fun load(ref: IconRef, sizePx: Int, style: IconStyle): Bitmap
}
