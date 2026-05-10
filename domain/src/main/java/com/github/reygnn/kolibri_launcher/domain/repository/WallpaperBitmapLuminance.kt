package com.github.reygnn.kolibri_launcher.domain.repository

/**
 * Computes the median WCAG luminance of a Kolibri-internal wallpaper
 * image, identified by its persisted URI string (`file://…` after the
 * copy-to-internal step in `WallpaperFileManager`).
 *
 * Returns `null` if the bitmap cannot be loaded (file gone, decode
 * failure, OOM, revoked permission). Callers treat `null` as
 * "classification unknown for this URI" and fall through to the
 * next signal source.
 *
 * Implementation in `:data` is Robolectric-bound because it touches
 * `android.graphics.Bitmap`. The interface lives in `:domain` so use
 * cases can depend on the abstraction without pulling in the
 * Android SDK.
 */
interface WallpaperBitmapLuminance {

    /**
     * Returns the median WCAG luminance of the image at [uri] in
     * the range `0.0` (black) to `1.0` (white). Median rather than
     * mean to suppress outlier pixels (highlights, sun on
     * photographic wallpapers).
     */
    suspend fun compute(uri: String): Float?
}
