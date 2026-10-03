package com.github.reygnn.launcher.feature.wallpaper

import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * The wallpaper half of a backup (SPEC_NYX_REWRITE 3a-7): collecting the layer images on export,
 * binding them back on import, the O2 rule "one file per layer", and the cleanup of copies no
 * restored layer claimed. The format stays each app's (F3): the layers live in the app's
 * section, a blob is referenced by its hash in `imageFileName`.
 *
 * Deliberately WITHOUT a dependency on `:feature-backup` (02.10.): the feature modules stand side
 * by side, the apps compose them. So this class works on plain types — files, hashes, and the
 * claiming/opening as functions the app passes in (`blobs::claim`, the content resolver).
 */
class WallpaperBackupBlobs @Inject constructor(
    private val fileManager: WallpaperFileManager,
    private val imageStore: WallpaperImageStore,
) {

    /**
     * What [collect] found: [files] are the images to back up, in blob order (the app turns each
     * into a blob source, then appends any blobs of its own); [rebind] writes the hashes back.
     */
    class Collected internal constructor(
        val files: List<File>,
        private val blobOfLayer: List<Int?>,
    ) {
        /**
         * The layers as they go into the section: a layer with an image gets `imageFileName` from
         * its blob's hash and loses its device-local `imageUri`; a layer without one keeps
         * `imageUri` and gets no `imageFileName`. [hashes] are in blob order and may run longer
         * than [files] (the app's own blobs follow).
         */
        fun rebind(layers: List<WallpaperLayerBackup>, hashes: List<String>): List<WallpaperLayerBackup> =
            layers.mapIndexed { index, layer ->
                val blob = blobOfLayer.getOrNull(index)
                if (blob != null) layer.copy(imageUri = null, imageFileName = hashes[blob]) else layer.copy(imageFileName = null)
            }
    }

    /** Export: per layer the local file to back up ([localFileOf] null = none, the layer has no blob). */
    fun collect(layers: List<WallpaperLayerBackup>, localFileOf: (String) -> File?): Collected {
        val files = ArrayList<File>()
        val blobOfLayer = layers.map { layer ->
            layer.imageUri?.let(localFileOf)?.let { file ->
                files += file
                files.size - 1
            }
        }
        return Collected(files, blobOfLayer)
    }

    /**
     * Import: one internal file per referenced blob hash, written into [into] (hash → internal URI)
     * as each copy lands — so a failure midway still leaves every copy made so far visible to the
     * caller's cleanup. [claim] hands over the staged blob as a file (the app passes
     * `blobs::claim`); null means rejected or missing — that hash is left out, and its layers are
     * dropped and counted by the caller (B9). The claimed staging file is deleted after its copy.
     */
    fun extract(
        referencedHashes: Collection<String>,
        claim: (String, File) -> File?,
        staging: File,
        into: MutableMap<String, String>,
    ) {
        for (hash in referencedHashes.toSet()) {
            val file = claim(hash, File(staging, "claimed-$hash")) ?: continue
            val internal = try {
                file.inputStream().use { fileManager.copyFromInputStream(it) }
            } finally {
                file.delete()
            }
            if (internal != null) into[hash] = internal.toString()
        }
    }

    /**
     * O2, the one place for both apps: every layer its own file. [perLayer] are the layers'
     * internal URIs in order; a URI an earlier layer already got is replaced by a fresh copy
     * (read through [open]), so removing one layer — which deletes its file right away — never
     * takes another layer's image with it. Equal content is not deduplicated anywhere: decisions
     * are made on file references only. Null stays null; a failed copy becomes null (the caller
     * drops and counts that layer).
     */
    fun assignOwnFiles(perLayer: List<String?>, open: (String) -> InputStream?): List<String?> {
        val taken = HashSet<String>()
        return perLayer.map { uri ->
            val own = when {
                uri == null -> null
                uri !in taken -> uri
                else -> freshCopyOf(uri, open)
            }
            own?.also { taken += it }
        }
    }

    private fun freshCopyOf(uri: String, open: (String) -> InputStream?): String? = try {
        open(uri)?.use { fileManager.copyFromInputStream(it) }?.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Catch kept (Expected error, four-category frame): an unreadable shared file drops only
        // the layer that needed its own copy; OOM extends Error → Throwable.
        TimberWrapper.silentError(e, "Failed to give a restored layer its own file")
        null
    }

    /**
     * Cleanup after an import, for the `finally`: deletes [unclaimedCopies] — the copies NO restored
     * layer claimed — through the store, i.e. only what the persisted state does not reference,
     * and nothing if that can't be read (fail closed, 3a-2c).
     *
     * **Only unclaimed copies, never claimed ones.** The caller claims a layer's file BEFORE its
     * save; if that save fails silently, the claimed files are not in the persisted state either,
     * and passing them here would delete them. Left out, they stay as orphans for the GC — the
     * safe direction.
     *
     * Runs under [NonCancellable]: a suspending cleanup in a `finally` would otherwise be skipped
     * after a cancellation. The cancellation itself keeps propagating afterwards.
     */
    suspend fun release(unclaimedCopies: Collection<String>) {
        if (unclaimedCopies.isEmpty()) return
        withContext(NonCancellable) { imageStore.deleteUnreferenced(unclaimedCopies) }
    }
}
