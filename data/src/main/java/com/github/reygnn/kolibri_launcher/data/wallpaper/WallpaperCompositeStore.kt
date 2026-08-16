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
import javax.inject.Inject

/**
 * Stores the flattened display-mode wallpaper composite (Option D,
 * WALLPAPER_DRAWER_HOME_REBUILD_SPEC §9.3): a single overwritten slot in
 * `filesDir/wallpaper_composite/`.
 *
 * DELIBERATELY outside `filesDir/wallpapers/`, so the orphan-GC
 * ([com.github.reygnn.kolibri_launcher.data.WallpaperFileManager.gcOrphans]) and
 * the backup — both of which only walk the `wallpapers/` dir — never touch this
 * DERIVED artifact. It is regenerated from the layers at every edit-commit, so it
 * is never backed up and never migrated (Rule 5).
 */
class WallpaperCompositeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private fun dir(): File =
        File(context.filesDir, COMPOSITE_DIR).also { if (!it.exists()) it.mkdirs() }

    private fun compositeFile(): File = File(dir(), COMPOSITE_FILE)

    /**
     * Persists [bitmap] as the composite (lossless WEBP — preserves alpha) and
     * returns its `file://` URI string, or `null` on failure. [bitmap] MUST be a
     * SOFTWARE bitmap: a HARDWARE bitmap cannot be compressed. The flatten
     * produces a software bitmap via `ZoomableImageView.composeToBitmap` (§9.2
     * Approach A).
     */
    suspend fun write(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val file = compositeFile()
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            }
            Uri.fromFile(file).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: compress on a large composite can OOM
            // (allocation boundary). A half-written file is useless — drop it.
            TimberWrapper.silentError(e, "Failed to write wallpaper composite")
            runCatching { file.delete() }
            null
        }
    }

    /** Deletes the composite slot (factory reset / no wallpaper). Idempotent. */
    fun clear() {
        try {
            val file = compositeFile()
            if (file.exists()) file.delete()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to clear wallpaper composite")
        }
    }

    companion object {
        const val COMPOSITE_DIR = "wallpaper_composite"
        const val COMPOSITE_FILE = "composite.webp"
    }
}
