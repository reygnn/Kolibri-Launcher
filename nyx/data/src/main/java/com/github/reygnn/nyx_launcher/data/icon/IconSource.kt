package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
import com.github.reygnn.nyx_launcher.home.model.IconRef

/**
 * Resolves + rasterizes an icon (the Android/system half of the loader). Split
 * out so the caching layer is testable with a fake. [monochrome] selects the
 * themed rendering. Called on an IO dispatcher.
 */
interface IconSource {
    suspend fun load(ref: IconRef, sizePx: Int, monochrome: Boolean): Bitmap
}
