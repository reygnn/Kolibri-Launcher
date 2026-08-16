package com.github.reygnn.kolibri_launcher.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Stores the flattened display-mode wallpaper composite (Option D,
 * WALLPAPER_DRAWER_HOME_REBUILD_SPEC §9.3): one file in
 * `filesDir/wallpaper_composite/`, written under a CONTENT-VERSIONED name
 * (`composite_<n>.webp`) — every flatten produces a NEW path.
 *
 * The versioned name is deliberate. Consumers key on the path string: the
 * in-memory bitmap cache (HomeFragment) and the AUTO-mode classifier's
 * `distinctUntilChanged`. A new composite ⇒ new path ⇒ a natural cache miss /
 * re-classification — no fragile "the path is fixed, so invalidate explicitly"
 * coupling (§9.4a). At most one composite file exists at a time: [write] deletes
 * any previous one first.
 *
 * DELIBERATELY outside `filesDir/wallpapers/`, so the orphan-GC
 * ([com.github.reygnn.kolibri_launcher.data.WallpaperFileManager.gcOrphans]) and
 * the backup — both of which only walk the `wallpapers/` dir — never touch this
 * DERIVED artifact. Never backed up, never migrated (Rule 5).
 */
class WallpaperCompositeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private fun dir(): File =
        File(context.filesDir, COMPOSITE_DIR).also { if (!it.exists()) it.mkdirs() }

    /**
     * Persists [bitmap] as the composite (lossless WEBP — preserves alpha) under a
     * fresh versioned name and returns its `file://` URI string, or `null` on
     * failure. Deletes any previous composite first (only the latest is kept).
     * [bitmap] MUST be a SOFTWARE bitmap: a HARDWARE bitmap cannot be compressed.
     */
    suspend fun write(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            deleteAll() // previous composite (the new file does not exist yet)
            val file = File(dir(), "$COMPOSITE_PREFIX${System.currentTimeMillis()}_${counter.getAndIncrement()}.webp")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            }
            Uri.fromFile(file).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: compress on a large composite can OOM
            // (allocation boundary). A half-written file is cleaned by the next
            // write()/clear()'s deleteAll().
            TimberWrapper.silentError(e, "Failed to write wallpaper composite")
            null
        }
    }

    /** Deletes every composite file (factory reset / no wallpaper). Idempotent. */
    fun clear() {
        try {
            deleteAll()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to clear wallpaper composite")
        }
    }

    private fun deleteAll() {
        dir().listFiles { f -> f.name.startsWith(COMPOSITE_PREFIX) }?.forEach { it.delete() }
    }

    companion object {
        const val COMPOSITE_DIR = "wallpaper_composite"
        private const val COMPOSITE_PREFIX = "composite_"
        private val counter = AtomicLong(0)
    }
}
