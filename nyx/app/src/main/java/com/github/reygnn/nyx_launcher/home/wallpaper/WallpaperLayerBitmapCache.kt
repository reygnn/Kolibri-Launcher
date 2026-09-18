package com.github.reygnn.nyx_launcher.home.wallpaper

import androidx.annotation.VisibleForTesting
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small multi-entry cache of decoded per-layer wallpaper bitmaps, keyed by the
 * layer's `file://` URI string.
 *
 * Nyx-local on purpose: the shared [com.github.reygnn.launcher.common.ui.wallpaper.WallpaperCompositeCache]
 * is SINGLE-entry (one composite / single-image texture) and cannot hold N layers —
 * so that cache buys nyx's multi-layer collage nothing. This one targets nyx's
 * symptoms directly.
 *
 * **Application-scoped** ([Singleton]): it survives `MainActivity` re-creation, which
 * is what makes returning to a recreated home (config change while backgrounded, or
 * the Activity reclaimed under memory pressure) cheap — the `FullRebuild` against a
 * fresh empty view finds every layer in the cache and skips the decode. An
 * Activity-field cache would be empty on the new instance and re-decode the whole
 * collage. (Kolibri gets the same survival from its own `@Singleton`
 * WallpaperCompositeCache; nyx keeps this per-layer one instead.)
 *
 * It also kills the delete-a-layer flicker WITHIN a live Activity: removing one
 * layer produces a [com.github.reygnn.launcher.common.ui.wallpaper.RebuildPlan.FullRebuild]
 * (the plan has no partial-remove), which `clearLayers()` + re-decodes every
 * REMAINING layer; on a slower GPU (A17) that re-decode is a visible flash, and the
 * cache turns it into instant hits. Adds and single-image replacements likewise get
 * faster; a transform-only edit keeps the same `file://` key, so its bitmap is
 * reused untouched.
 *
 * Bounded by total decoded bytes ([maxBytes], LRU eviction). On eviction it only
 * DROPS the reference — it never `recycle()`s — because an evicted bitmap may still
 * be on screen (the never-recycle invariant the shared cache also holds); GC
 * reclaims it once the view releases it too. A single oversized layer is kept
 * rather than evicted-to-empty. [clear] releases everything when the wallpaper is
 * removed AND when the app is backgrounded / under memory pressure
 * (`NyxApplication.onTrimMemory` >= TRIM_MEMORY_BACKGROUND), so an app-scoped
 * lifetime never means "held forever" or "held through background".
 *
 * Access is [Synchronized]: the binder calls [get] on the render (main) thread and
 * [put]/[putIfCurrent] from its IO decode hop, while [clear] runs on the main
 * thread — so every public method takes the monitor.
 */
@Singleton
class WallpaperLayerBitmapCache @VisibleForTesting internal constructor(
    private val maxBytes: Long,
) {
    /** Hilt entry point — app default budget. Tests use the primary constructor. */
    @Inject constructor() : this(DEFAULT_MAX_BYTES)

    // The byte size is captured ONCE at insertion and stored with the entry, so
    // drop()/trim() never re-read Bitmap.allocationByteCount — which returns 0 for a
    // recycled bitmap and would otherwise leave currentBytes permanently inflated.
    private class Entry(val decoded: DecodedWallpaperBitmap, val bytes: Long)

    // accessOrder = true → the map's iteration order runs least-recently-accessed
    // first, so eviction in trim() drops the true LRU entry. get() reorders on hit.
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0L

    // Bumped on every clear(). A decode that captured an older generation (via
    // [generation]) before a clear lands drops its result through [putIfCurrent]
    // instead of stranding a bitmap in the now-cleared, app-scoped cache.
    private var generation = 0L

    /** The cached decode for [key], or null on a miss / recycled bitmap. */
    @Synchronized
    fun get(key: String): DecodedWallpaperBitmap? {
        val hit = entries[key] ?: return null
        if (hit.decoded.bitmap.isRecycled) {
            drop(key)
            return null
        }
        return hit.decoded
    }

    /**
     * The current cache generation. Capture it BEFORE a decode and hand it to
     * [putIfCurrent] so a decode that races a [clear] (wallpaper removed mid-flight)
     * does not repopulate the cache.
     */
    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun put(key: String, decoded: DecodedWallpaperBitmap) {
        // Replace any existing entry's byte accounting before re-inserting.
        drop(key)
        val bytes = sizeOf(decoded)
        entries[key] = Entry(decoded, bytes)
        currentBytes += bytes
        trim()
    }

    /**
     * [put], unless [expectedGeneration] no longer matches the current [generation] —
     * i.e. a [clear] happened since the caller captured it, so the wallpaper this
     * decode was for is gone. Returns true if stored, false if dropped as stale.
     */
    @Synchronized
    fun putIfCurrent(key: String, decoded: DecodedWallpaperBitmap, expectedGeneration: Long): Boolean {
        if (expectedGeneration != generation) return false
        put(key, decoded)
        return true
    }

    /** Drops all references (wallpaper removed / reset) and bumps [generation]. Never recycles. */
    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0L
        generation++
    }

    private fun drop(key: String) {
        entries.remove(key)?.let { currentBytes -= it.bytes }
    }

    private fun trim() {
        // Evict least-recently-used entries until within budget, but keep at least
        // one (the just-put entry) so a single oversized layer is cached, not lost.
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && entries.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= eldest.value.bytes
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
