package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the ONE decoded display wallpaper bitmap
 * (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4) — the flattened composite for a multi-layer
 * wallpaper, OR the single image for a single-layer one. Both render through the single-image
 * path and would otherwise be re-produced on every drawer→home view re-creation: the composite
 * re-flattened (O(N) software decode + compose, tens–hundreds of ms), the single image
 * re-decoded from disk. Caching the result makes drawer→home a ~0 ms one-texture re-attach.
 *
 * Keyed by content, single-entry: the multi-layer composite by its `compositeKey`
 * (`composite://<hash>`, produced by the delegate's warm), the single image by its `file://`
 * URI. Any change — new layers/transform (new hash) or a new picked image (new URI) — is a new
 * key, so [put] simply replaces the entry (no explicit invalidation on change). [invalidate]
 * drops the reference on wallpaper clear / factory reset. There is no on-disk composite in v4 —
 * no file, no WEBP, no store.
 *
 * Application-scoped (survives the fragment view). Never recycles its bitmap — it drops the
 * reference and relies on GC: a replaced-out bitmap that may still be on screen keeps drawing
 * until the next render, and GC reclaims it once both this cache and the view release it. Holds
 * one ~10 MB HARDWARE bitmap so the wallpaper re-attaches instantly across the view re-creation
 * that drawer→home triggers.
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
     * Drops the held bitmap reference so GC can reclaim the ~10 MB composite once
     * the view has also released it. Called when the wallpaper is removed / reset
     * (AUDIT-20 F3): without a wallpaper on screen nothing ever queries this cache
     * again, so the entry would otherwise stay resident until a later [put] replaces
     * it or the process dies. Does NOT recycle — the never-recycle invariant holds
     * (the replaced-out bitmap may still be drawing); it only releases this side's
     * reference.
     */
    @Synchronized
    fun invalidate() {
        cached = null
        cachedPath = null
    }

    /**
     * Drops the held bitmap reference IF it is cached under a key other than [currentKey]
     * (AUDIT-20 F12). A resolution change (rotate/fold) versions the composite key, so the
     * previous-resolution entry becomes a guaranteed miss for [currentKey]; without this it
     * would stay resident — a stranded ~10 MB bitmap nothing queries — until a later
     * successful [put] happens to replace it. A warm for the new key that FAILS never does
     * that replace, which is the leak this closes. No-op when the cache already holds
     * [currentKey] (the live entry) or is empty, so it never drops the current display
     * bitmap. Does NOT recycle — the never-recycle invariant holds; it only releases this
     * side's reference.
     */
    @Synchronized
    fun invalidateIfNotKey(currentKey: String) {
        if (cached != null && cachedPath != currentKey) {
            cached = null
            cachedPath = null
        }
    }
}
