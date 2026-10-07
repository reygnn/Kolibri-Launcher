package com.github.reygnn.nyx_launcher.home.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperCompositeKey
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What the home draws, and where its bitmaps come from (SPEC_NYX_REWRITE 3b-6, E3) — the read
 * side in ONE place, testable without a device:
 *  - [displayTargetFor]: in DISPLAY mode a warmed composite is shown as one image
 *    (`composite://<key>`, as in Kolibri); in the EDITOR, or without a cache hit, the real
 *    multi-layer state — the editor always works on its layers (R1).
 *  - [load]: a `composite://` target comes from the composite; EVERY other target still goes
 *    through [layerCache] with its generation guard, as before 3b-6 — the editor keeps its
 *    protection against re-decoding the other layers after a removal. Whether that cache becomes
 *    a shared part for both apps' editors or goes is decided after 3b-6, not by E3.
 */
class NyxWallpaperRenderSource(
    private val composite: WallpaperComposite,
    private val layerCache: WallpaperLayerBitmapCache,
    private val ioDispatcher: CoroutineDispatcher,
    /** Bounded decode of one image file (I/O; may throw — [load] catches at its boundary). */
    private val decode: (Uri) -> DecodedWallpaperBitmap?,
) {

    /** The state to render: the composite as a single image in display mode on a hit, else [state]. */
    fun displayTargetFor(state: WallpaperState, isEditMode: Boolean, widthPx: Int, heightPx: Int): WallpaperState {
        if (isEditMode || state.layerCount < 2) return state
        val key = composite.cachedKeyFor(state, widthPx, heightPx) ?: return state
        return WallpaperState.single(key)
    }

    /** The binder's bitmap loader. Never throws except cancellation; null means "skip this layer". */
    suspend fun load(uri: Uri): DecodedWallpaperBitmap? {
        val key = uri.toString()
        // A composite:// key is synthetic (not a file): resolve it from the composite only.
        if (key.startsWith(WallpaperCompositeKey.SCHEME)) return composite.cachedBitmap(key)
        // Moved unchanged from MainActivity's loader (3b-6) — only the decode call is the
        // injected [decode] and the cache field is [layerCache]:
        // Cache hit → return the already-decoded layer instantly (no IO hop),
        // so deleting one layer doesn't re-decode the rest and flash.
        return layerCache.get(key) ?: run {
            // Capture the cache generation BEFORE the decode: if a clear() lands
            // during the IO hop (wallpaper removed mid-flight), putIfCurrent drops
            // the result instead of stranding it in the app-scoped cache.
            val generation = layerCache.generation()
            withContext(ioDispatcher) {
                // BitmapLoader contract: return null on failure, let only cancellation
                // escape. The decode does NOT catch internally — openInputStream can
                // throw FileNotFoundException/SecurityException and decode can OOM
                // (Throwable). Without this guard the throw would escape bind() → the
                // unguarded collect/launch → crash the HOME activity.
                try {
                    decode(uri)?.also { layerCache.putIfCurrent(key, it, generation) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Catch kept — expected error at the decode boundary (I/O, OOM); cancellation rethrown above.
                    TimberWrapper.silentError(e, "Error loading wallpaper bitmap from $uri")
                    null
                }
            }
        }
    }
}
