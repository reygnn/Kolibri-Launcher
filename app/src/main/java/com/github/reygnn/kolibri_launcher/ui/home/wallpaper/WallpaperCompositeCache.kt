package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the ONE decoded display composite (Option D,
 * WALLPAPER_DRAWER_HOME_REBUILD_SPEC §9.4). This is the actual latency win: on a
 * device the composite decode is ~90 ms (a single non-parallelisable lossless
 * WEBP), which is not faster than the parallel per-layer decode — so the win comes
 * from NOT decoding at all on each drawer→home. Holding one ~10 MB HARDWARE bitmap
 * (a size §5 flagged as affordable, unlike the N-layer cache) lets the wallpaper
 * re-attach instantly across the view re-creation that drawer→home triggers.
 *
 * Application-scoped (survives the fragment view). Safe because the wallpaper view
 * never recycles its bitmaps (it drops references and relies on GC), so a cached
 * reference cannot be pulled out from under a still-drawing view. [invalidate]
 * therefore only drops the reference — never recycles — so the composite that is
 * still on screen keeps drawing until the next render replaces it, and GC reclaims
 * it once both this cache and the view have released it.
 */
@Singleton
class WallpaperCompositeCache @Inject constructor() {

    private var cachedPath: String? = null
    private var cached: DecodedWallpaperBitmap? = null

    /** The cached composite for [path], or null on miss / recycled bitmap. */
    @Synchronized
    fun get(path: String): DecodedWallpaperBitmap? {
        val hit = cached ?: return null
        if (cachedPath != path) return null
        if (hit.bitmap.isRecycled) {
            cached = null
            cachedPath = null
            return null
        }
        return hit
    }

    @Synchronized
    fun put(path: String, decoded: DecodedWallpaperBitmap) {
        cachedPath = path
        cached = decoded
    }

    /**
     * Drops the cached composite so the next display re-decodes the fresh file.
     * Called when a new composite is written (edit-commit). No recycle — see the
     * class KDoc.
     */
    @Synchronized
    fun invalidate() {
        cachedPath = null
        cached = null
    }
}
