package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
import com.github.reygnn.nyx_launcher.home.model.IconRef

/**
 * The single read path for rendered icons (ICON_LOADER_SPEC §1). [bitmap] is
 * cache-backed and never runs on the main thread (ICL-INV-1); [evict] and [trim]
 * are synchronous index operations (no IO).
 *
 * Implementation (rasterizer + disk cache + request coalescing) is hand-rolled —
 * no Coil/Glide (CLAUDE.md rule 20). This interface is the seam `:app` adapters
 * consume behind the stale-binding guard (ICL-INV-9).
 */
interface IconLoader {

    /** Cache-backed icon for [ref] at [sizePx]; resolves + composites on a miss. */
    suspend fun bitmap(ref: IconRef, sizePx: Int): Bitmap

    /** Drop all memory + disk entries for [pkg] (wired to PackageUpdateReceiver). */
    fun evict(pkg: String)

    /** Respond to `onTrimMemory` — see [LruBudget.trim]. */
    fun trim(level: Int)
}
