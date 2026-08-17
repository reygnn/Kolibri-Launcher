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
 * coupling (§9.4a). [write] creates a NEW composite (temp file + atomic rename) but
 * does NOT drop the previous one — cleanup is decoupled into [prune] / [delete],
 * which the delegate calls only AFTER it has decided the new path is the one to keep
 * (AUDIT-20 F1 write-then-swap + F7 prune-after-persist). A superseded flatten, whose
 * path is never persisted, therefore never unlinks the composite the current state
 * still references; it drops its own just-written file via [delete] instead.
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
     * failure. [bitmap] MUST be a SOFTWARE bitmap: a HARDWARE bitmap cannot be
     * compressed.
     *
     * Write-then-swap ordering (AUDIT-20 F1/F4): the composite is compressed into a
     * temp file and atomically renamed to its final versioned name. Older composites
     * are NOT dropped here (F7) — that is [prune]'s job, invoked by the delegate only
     * once it has confirmed this path is the one being persisted. Writing a NEW file
     * and never touching the previous one means a failed or superseded write cannot
     * unlink the still-referenced composite. The `compress()` boolean is checked (F4):
     * a `false` return WITHOUT a throw (encoder refusal, unsupported config) leaves a
     * partial/corrupt file, so the temp is deleted and `null` returned rather than a
     * path to a broken composite.
     */
    suspend fun write(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        var tmp: File? = null
        try {
            val dir = dir()
            val file = File(dir, "$COMPOSITE_PREFIX${System.currentTimeMillis()}_${counter.getAndIncrement()}.webp")
            tmp = File(dir, "${file.name}$TMP_SUFFIX")
            val compressed = FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            }
            // F4: compress() can return false WITHOUT throwing — never persist a path
            // to the partial file it leaves behind. Rename can also fail; either way
            // the temp is dropped and any previous composite is left untouched.
            if (!compressed || !tmp.renameTo(file)) {
                tmp.delete()
                return@withContext null
            }
            tmp = null // renamed into place; ownership transferred to `file`
            Uri.fromFile(file).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: compress on a large composite can OOM
            // (allocation boundary). Clean up the temp file; any older composite is
            // left intact as the fallback.
            tmp?.delete()
            TimberWrapper.silentError(e, "Failed to write wallpaper composite")
            null
        }
    }

    /**
     * Drops every composite EXCEPT the one behind [keepUri] (the just-persisted path),
     * plus any stray `.tmp` leftovers from an earlier crashed write (they carry the
     * [COMPOSITE_PREFIX] too). Called by the delegate AFTER a successful state save
     * (AUDIT-20 F7): pruning is deliberately decoupled from [write] so a superseded
     * flatten — one whose path is never persisted — cannot unlink the composite the
     * current state still references. Idempotent.
     */
    suspend fun prune(keepUri: String): Unit = withContext(Dispatchers.IO) {
        val keepPath = Uri.parse(keepUri).path ?: return@withContext
        try {
            val keep = File(keepPath)
            dir().listFiles { f -> f.name.startsWith(COMPOSITE_PREFIX) && f != keep }
                ?.forEach { it.delete() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No suspension point in the try body (blocking file I/O only); the
            // CancellationException arm mirrors write() for structural consistency.
            TimberWrapper.silentError(e, "Failed to prune wallpaper composites")
        }
    }

    /**
     * Deletes the single composite behind [uri] — used when a flatten is superseded
     * before its path is persisted, so its just-written file is dropped rather than
     * left as an orphan (AUDIT-20 F7). Idempotent; every other composite is untouched.
     */
    suspend fun delete(uri: String): Unit = withContext(Dispatchers.IO) {
        val path = Uri.parse(uri).path ?: return@withContext
        try {
            File(path).delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No suspension point in the try body (blocking file I/O only); the
            // CancellationException arm mirrors write() for structural consistency.
            TimberWrapper.silentError(e, "Failed to delete wallpaper composite")
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
        private const val TMP_SUFFIX = ".tmp"
        private val counter = AtomicLong(0)
    }
}
