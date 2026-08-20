package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import java.security.MessageDigest

/**
 * The in-memory composite cache key (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4 §2/§3a).
 *
 * The flattened multi-layer composite lives ONLY in [WallpaperCompositeCache], keyed by a
 * content hash of everything the flatten reads. A miss re-flattens, so the key is the single
 * correctness condition: it MUST cover every pixel-affecting input, or two visually-different
 * composites collide on one key and the stale one is served from RAM.
 *
 * The result is an opaque `composite://<sha256>` string that reuses the whole single-image
 * render/cache path (the fragment feeds it to `SwitchToSingleLayer` and resolves it back from
 * the cache in `loadBitmapFromUri`) — see §3a. It is NEVER a real file URI, so it can never
 * collide with a single-layer `file://` key in the single-entry cache.
 *
 * **One metric source (§3a pin):** the width/height terms MUST come from the SAME call on both
 * the warm-write side (`WallpaperDelegate`) and the render-read side (`HomeFragment`) —
 * `context.resources.displayMetrics.{widthPixels,heightPixels}`. A divergent source yields two
 * different keys → permanent miss → the warm re-flattens forever and the hit never lands.
 */
object WallpaperCompositeKey {

    const val SCHEME = "composite://"

    /**
     * Bump when the render budget / texture bound / captureSampleSize compensation changes, so a
     * cross-update resolution change is a natural miss (no data migration — the cache is derived).
     */
    private const val RENDER_BUDGET_VERSION = 1

    /**
     * Content key for [state] at the given display dimensions. Only meaningful for a multi-layer
     * state (the single-layer path keys on its `file://` URI directly). `layerBackgroundColor` is
     * hard-wired transparent today and omitted; add it here the day it becomes configurable.
     */
    fun of(state: WallpaperState, widthPx: Int, heightPx: Int): String {
        val sb = StringBuilder(64)
        sb.append('v').append(RENDER_BUDGET_VERSION)
            .append('|').append(widthPx).append('x').append(heightPx)
        // Order-sensitive: z-order changes the composite, so hash layers in list order.
        for (l in state.layers) {
            sb.append('|')
                .append(l.imageUri).append(';')
                .append(l.scale).append(';')
                .append(l.translateX).append(';')
                .append(l.translateY).append(';')
                .append(l.captureSampleSize)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(SCHEME.length + digest.size * 2).append(SCHEME)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
