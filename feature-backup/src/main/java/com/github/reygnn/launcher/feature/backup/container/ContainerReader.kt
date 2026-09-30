package com.github.reygnn.launcher.feature.backup.container

import com.github.reygnn.launcher.core.CappedInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads a container (E5a) from a caller-opened [InputStream] (U1) into a staging dir.
 *
 * - The first entry must be `manifest.json`; otherwise the archive is a pre-E5a backup
 *   (it has `backup.json`) or invalid — also when a hand-repacked archive moved the
 *   manifest further down.
 * - The manifest is read with a cap (U2), its header decoded by [decodeHeader]; a newer
 *   major version is rejected, a newer minor accepted.
 * - Each `blobs/<sha256>` entry listed in the table streams into staging while being
 *   hashed and size-checked; a mismatch drops just that blob and is reported. Entries
 *   without a table row are dropped.
 * - Every non-[ContainerRead.Ok] outcome — and any exception, cancellation included —
 *   leaves nothing staged. A read failure that is not a format problem (disk / provider
 *   error) propagates as the IOException it is.
 */
class ContainerReader(
    private val ioDispatcher: CoroutineDispatcher,
    private val limits: ContainerLimits = ContainerLimits(),
) {

    suspend fun read(
        input: InputStream,
        stagingDir: File,
        decodeHeader: (ByteArray) -> ManifestHeader?,
    ): ContainerRead = withContext(ioDispatcher) {
        val staged = LinkedHashMap<String, File>()
        val archive = CappedInputStream(NonClosing(input), limits.maxArchiveBytes + 1)
        var ok = false
        try {
            val result = ZipInputStream(archive).use { zip -> readZip(zip, archive, stagingDir, staged, decodeHeader) }
            if (archive.limitReached) return@withContext ContainerRead.TooLarge(ContainerFormat.CAP_ARCHIVE)
            ok = result is ContainerRead.Ok
            result
        } catch (e: ZipException) {
            ContainerRead.Invalid("not a readable ZIP: ${e.message}")
        } catch (e: EOFException) {
            // The archive cap ends the stream early, which ZipInputStream reports as a
            // truncated entry — that is "too large"; without the cap it is a truncated file.
            if (archive.limitReached) ContainerRead.TooLarge(ContainerFormat.CAP_ARCHIVE) else ContainerRead.Invalid("truncated archive")
        } catch (e: IOException) {
            // Any other I/O failure is not a format problem (a disk or provider error): it
            // propagates, so the caller reports it as such instead of "invalid backup".
            if (archive.limitReached) ContainerRead.TooLarge(ContainerFormat.CAP_ARCHIVE) else throw e
        } finally {
            if (!ok) {
                staged.values.forEach { it.delete() }
                // a blob interrupted mid-copy (cap, I/O error, cancellation) leaves its .part
                stagingDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
            }
        }
    }

    private suspend fun readZip(
        zip: ZipInputStream,
        archive: CappedInputStream,
        stagingDir: File,
        staged: MutableMap<String, File>,
        decodeHeader: (ByteArray) -> ManifestHeader?,
    ): ContainerRead {
        val first = zip.nextEntry ?: return ContainerRead.Invalid("empty archive")
        if (first.name != ContainerFormat.MANIFEST_ENTRY) return classifyWithoutLeadingManifest(zip, first.name)

        val manifestBytes = zip.readNBytes(limits.maxManifestBytes + 1)
        if (manifestBytes.size > limits.maxManifestBytes) return ContainerRead.TooLarge("manifest")
        val header = decodeHeader(manifestBytes) ?: return ContainerRead.Invalid("manifest unreadable")
        if (header.formatMajor != ContainerFormat.SUPPORTED_MAJOR) {
            return ContainerRead.UnsupportedFormat("${header.formatMajor}.${header.formatMinor}")
        }
        if (header.blobs.size > limits.maxBlobCount) return ContainerRead.TooLarge("blob count")
        val table = HashMap<String, BlobEntry>()
        for (blob in header.blobs) {
            if (!ContainerFormat.isSha256Hex(blob.sha256)) return ContainerRead.Invalid("malformed blob hash in manifest")
            if (blob.size < 0 || blob.size > limits.maxBlobBytes) return ContainerRead.TooLarge("blob")
            table[blob.sha256] = blob
        }

        stagingDir.mkdirs()
        val rejected = ArrayList<RejectedBlob>()
        val unlisted = ArrayList<String>()
        var entries = 1
        while (true) {
            currentCoroutineContextCheck()
            val entry = zip.nextEntry ?: break
            if (++entries > limits.maxBlobCount + 1) return ContainerRead.TooLarge("entry count")
            if (archive.limitReached) return ContainerRead.TooLarge(ContainerFormat.CAP_ARCHIVE)
            val hash = entry.name.removePrefix(ContainerFormat.BLOB_DIR)
            val expected = if (entry.name.startsWith(ContainerFormat.BLOB_DIR)) table[hash] else null
            if (expected == null || hash in staged || rejected.any { it.sha256 == hash }) {
                unlisted += entry.name // skipped: ZipInputStream discards the rest of the entry
                continue
            }
            val part = File(stagingDir, "$hash.part")
            val blobIn = CappedInputStream(NonClosing(zip), expected.size + 1)
            val digest = sha256()
            val size = part.outputStream().use { copyHashing(blobIn, it, digest) }
            val reason = when {
                blobIn.limitReached || size != expected.size -> BlobRejection.SIZE_MISMATCH
                digest.hex() != hash -> BlobRejection.HASH_MISMATCH
                else -> null
            }
            if (reason != null) {
                part.delete()
                rejected += RejectedBlob(hash, reason)
            } else {
                val target = File(stagingDir, hash)
                if (!part.renameTo(target)) {
                    part.delete()
                    return ContainerRead.Invalid("could not stage blob $hash")
                }
                staged[hash] = target
            }
        }
        val missing = table.keys.filter { it !in staged && rejected.none { r -> r.sha256 == it } }
        return ContainerRead.Ok(manifestBytes, header, staged.toMap(), rejected, missing, unlisted)
    }

    /** No leading manifest: a pre-E5a backup if `backup.json` is there, invalid otherwise. */
    private fun classifyWithoutLeadingManifest(zip: ZipInputStream, firstName: String): ContainerRead {
        var name: String? = firstName
        var seen = 0
        var manifestLater = false
        while (name != null && seen++ <= limits.maxBlobCount + 1) {
            if (name == ContainerFormat.LEGACY_ENTRY) return ContainerRead.LegacyFormat
            if (name == ContainerFormat.MANIFEST_ENTRY) manifestLater = true
            name = zip.nextEntry?.name
        }
        return ContainerRead.Invalid(
            if (manifestLater) "manifest.json must be the first entry (archive was repacked)"
            else "no manifest.json",
        )
    }

    private suspend fun currentCoroutineContextCheck() = kotlin.coroutines.coroutineContext.ensureActive()

    /** Lets a ZipInputStream / cap wrapper be closed without closing the caller's stream. */
    private class NonClosing(source: InputStream) : FilterInputStream(source) {
        override fun close() = Unit
    }
}
