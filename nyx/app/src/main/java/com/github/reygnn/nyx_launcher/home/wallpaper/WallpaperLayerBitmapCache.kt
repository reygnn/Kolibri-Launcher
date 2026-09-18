package com.github.reygnn.nyx_launcher.home.wallpaper

import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap

/**
 * Small multi-entry cache of decoded per-layer wallpaper bitmaps, keyed by the
 * layer's `file://` URI string.
 *
 * Nyx-local on purpose: the shared [com.github.reygnn.launcher.common.ui.wallpaper.WallpaperCompositeCache]
 * is SINGLE-entry (one composite / single-image texture) and cannot hold N layers,
 * and nyx has no drawer→home view-teardown seam to reattach a composite across —
 * so that cache buys nyx nothing. This one targets a different symptom.
 *
 * Purpose — kill the delete-a-layer flicker: removing one layer produces a
 * [com.github.reygnn.launcher.common.ui.wallpaper.RebuildPlan.FullRebuild] (the plan
 * has no partial-remove), which `clearLayers()` + re-decodes every REMAINING layer.
 * On a slower GPU (A17) that re-decode is a visible flash. With this cache the
 * surviving layers are instant hits, so only genuinely new images ever decode
 * (adds and single-image replacements likewise get faster). A transform-only edit
 * keeps the same `file://` key, so its bitmap is reused untouched.
 *
 * Bounded by total decoded bytes ([maxBytes], LRU eviction). On eviction it only
 * DROPS the reference — it never `recycle()`s — because an evicted bitmap may still
 * be on screen (the never-recycle invariant the shared cache also holds); GC
 * reclaims it once the view releases it too. A single oversized layer is kept
 * rather than evicted-to-empty.
 *
 * Access is [Synchronized]: the binder calls [get]/[put] from its IO decode hop
 * (serial latest-wins render), and [clear] runs on the main thread.
 */
class WallpaperLayerBitmapCache(private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    // accessOrder = true → the map's iteration order runs least-recently-accessed
    // first, so eviction in trim() drops the true LRU entry. get() reorders on hit.
    private val entries = LinkedHashMap<String, DecodedWallpaperBitmap>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0L

    /** The cached decode for [key], or null on a miss / recycled bitmap. */
    @Synchronized
    fun get(key: String): DecodedWallpaperBitmap? {
        val hit = entries[key] ?: return null
        if (hit.bitmap.isRecycled) {
            drop(key)
            return null
        }
        return hit
    }

    @Synchronized
    fun put(key: String, decoded: DecodedWallpaperBitmap) {
        // Replace any existing entry's byte accounting before re-inserting.
        drop(key)
        entries[key] = decoded
        currentBytes += sizeOf(decoded)
        trim()
    }

    /** Drops all references (wallpaper removed / reset). Never recycles. */
    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0L
    }

    private fun drop(key: String) {
        entries.remove(key)?.let { currentBytes -= sizeOf(it) }
    }

    private fun trim() {
        // Evict least-recently-used entries until within budget, but keep at least
        // one (the just-put entry) so a single oversized layer is cached, not lost.
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && entries.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= sizeOf(eldest.value)
            // Reference-drop only — NEVER recycle: the bitmap may still be drawing.
            iterator.remove()
        }
    }

    private fun sizeOf(decoded: DecodedWallpaperBitmap): Long =
        decoded.bitmap.allocationByteCount.toLong().coerceAtLeast(1L)

    private companion object {
        // ~64 MB of decoded layers — enough for a handful of bounded full-screen
        // HARDWARE layers without unbounded growth across many add/remove edits.
        const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
    }
}
