package com.github.reygnn.launcher.feature.backup.container

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a container (E5a). Works on a caller-opened [OutputStream] (U1: SAF opening and
 * `writeOrDiscard` stay with the caller) and never closes it.
 *
 * Two passes, because the manifest comes first and names every blob by its hash:
 *  1. hash every blob, build the table — equal content lands in it once (dedup, B6);
 *  2. write `manifest.json`, then copy each distinct blob to `blobs/<sha256>`, hashing
 *     again. A blob that changed in between aborts with [BlobChangedDuringExportException]
 *     — the caller discards the half-written document (U3).
 * Cancellation is checked between blobs and propagates.
 */
class ContainerWriter(private val ioDispatcher: CoroutineDispatcher) {

    /**
     * @param manifest builds the manifest bytes from each source's hash (same order as
     *   [blobs], so sections can reference them) and the final, deduplicated blob table.
     * @return the blob table that was written.
     */
    suspend fun write(
        output: OutputStream,
        blobs: List<BlobSource>,
        manifest: (sourceHashes: List<String>, table: List<BlobEntry>) -> ByteArray,
    ): List<BlobEntry> = withContext(ioDispatcher) {
        val table = LinkedHashMap<String, Pair<BlobEntry, BlobSource>>()
        val sourceHashes = ArrayList<String>(blobs.size)
        for (source in blobs) {
            ensureActive()
            val digest = sha256()
            val size = source.open().use { copyHashing(it, null, digest) }
            val hash = digest.hex()
            sourceHashes += hash
            if (hash !in table) table[hash] = BlobEntry(hash, size, source.mediaType) to source
        }
        val entries = table.values.map { it.first }
        val manifestBytes = manifest(sourceHashes, entries)

        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry(ContainerFormat.MANIFEST_ENTRY))
        zip.write(manifestBytes)
        zip.closeEntry()
        for ((entry, source) in table.values) {
            ensureActive()
            zip.putNextEntry(ZipEntry(ContainerFormat.BLOB_DIR + entry.sha256))
            val digest = sha256()
            val size = source.open().use { copyHashing(it, zip, digest) }
            zip.closeEntry()
            if (size != entry.size || digest.hex() != entry.sha256) {
                throw BlobChangedDuringExportException(entry.sha256)
            }
        }
        zip.finish() // completes the archive without closing the caller's stream
        zip.flush()
        entries
    }
}
