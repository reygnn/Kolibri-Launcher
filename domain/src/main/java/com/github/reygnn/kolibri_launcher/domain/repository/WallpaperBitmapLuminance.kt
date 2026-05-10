package com.github.reygnn.kolibri_launcher.domain.repository

/**
 * Computes the median WCAG luminance of a Kolibri-internal wallpaper
 * image, identified by its persisted URI string (`file://…` after the
 * copy-to-internal step in `WallpaperFileManager`).
 *
 * Returns `null` for any of:
 * - bitmap cannot be loaded (file gone, decode failure, OOM,
 *   revoked permission)
 * - the image's effectively-opaque pixel coverage is below the
 *   impl's gate (i.e., the layer is too transparent to dominate
 *   visual perception; the system wallpaper shows through)
 *
 * Callers treat `null` uniformly as "classification unknown for
 * this URI, fall through to the next signal source" — they don't
 * need to distinguish the two reasons.
 *
 * Implementation in `:data` is Robolectric-bound because it touches
 * `android.graphics.Bitmap`. The interface lives in `:domain` so use
 * cases can depend on the abstraction without pulling in the
 * Android SDK.
 */
interface WallpaperBitmapLuminance {

    /**
     * Returns the median WCAG luminance of the image at [uri] in
     * the range `0.0` (black) to `1.0` (white), or `null` per the
     * class KDoc. Median rather than mean to suppress outlier
     * pixels (highlights, sun on photographic wallpapers). The
     * median is computed over effectively-opaque pixels only —
     * transparent regions don't contribute.
     */
    suspend fun compute(uri: String): Float?
}
