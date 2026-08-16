package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the ONE decoded display wallpaper bitmap (Option D,
 * WALLPAPER_DRAWER_HOME_REBUILD_SPEC §9.4) — the flattened composite for a
 * multi-layer wallpaper, OR the single image for a single-layer one. Both render
 * through `applySingleLayer` and re-decode from disk on every drawer→home, so both
 * win from NOT decoding at all. (The composite motivated it; the single-layer case
 * has the same re-decode cost and is included via the broader cache gate in
 * HomeFragment.) The composite decode is ~90 ms (a single non-parallelisable
 * lossless WEBP), which is not faster than the parallel per-layer decode — so the
 * win is the cache, not the single-image render. Holding one ~10 MB HARDWARE bitmap
 * (a size §5 flagged as affordable, unlike the N-layer cache) lets the wallpaper
 * re-attach instantly across the view re-creation that drawer→home triggers.
 *
 * Application-scoped (survives the fragment view). Purely key-based: a new
 * composite gets a VERSIONED path (`WallpaperCompositeStore`), so [put] with the
 * new key simply replaces the entry — no explicit invalidation needed. Safe
 * because the wallpaper view never recycles its bitmaps (it drops references and
 * relies on GC): the replaced-out bitmap that may still be on screen keeps drawing
 * until the next render, and GC reclaims it once both this cache and the view have
 * released it (this cache never recycles).
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
}
