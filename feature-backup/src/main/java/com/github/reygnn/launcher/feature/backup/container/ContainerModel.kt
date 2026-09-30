package com.github.reygnn.launcher.feature.backup.container

import com.github.reygnn.launcher.core.AppConstants
import java.io.File
import java.io.InputStream

/**
 * Backup container format (SPEC_NYX_REWRITE E5a), the layer below any app schema:
 *
 * ```
 * backup.zip
 *  ├── manifest.json   always the FIRST entry
 *  └── blobs/<sha256>  one entry per distinct content, name = hash
 * ```
 * The manifest says which blobs exist (hash, size, media type); readers never infer
 * meaning from paths. This layer knows no JSON: it handles ZIP, hashing, staging and
 * caps, and delegates the manifest to a codec (see ContainerManifestCodec).
 */
object ContainerFormat {
    const val MANIFEST_ENTRY = "manifest.json"
    const val BLOB_DIR = "blobs/"
    /** The pre-E5a Kolibri archive's root entry — seen without a manifest = legacy backup. */
    const val LEGACY_ENTRY = "backup.json"
    /** Readers accept this major only; any minor (unknown fields are ignored). */
    const val SUPPORTED_MAJOR = 1
    const val CURRENT_VERSION = "1.0"
    /** `TooLarge.what` of the whole-archive cap — also what a too large declared size reports. */
    const val CAP_ARCHIVE = "archive"

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
    /** Hashes become file names in the staging dir, so only well-formed hex is accepted. */
    fun isSha256Hex(value: String): Boolean = SHA256_HEX.matches(value)
}

/** One row of the manifest's blob table. */
data class BlobEntry(val sha256: String, val size: Long, val mediaType: String)

/** What the container layer needs from the manifest: its version and blob table. */
data class ManifestHeader(val formatMajor: Int, val formatMinor: Int, val blobs: List<BlobEntry>)

/** A blob to export. [open] is called twice (hash pass, copy pass) and must give the same bytes. */
class BlobSource(val mediaType: String, val open: () -> InputStream)

/**
 * Import caps. Archive and blob sizes reuse the shared backup budget; the manifest cap
 * is U2 (5 MiB, also on the preview path); the blob count bounds hostile manifests.
 */
data class ContainerLimits(
    val maxArchiveBytes: Long = AppConstants.MAX_BACKUP_SIZE_BYTES,
    val maxManifestBytes: Int = 5 * 1024 * 1024,
    val maxBlobBytes: Long = AppConstants.MAX_BACKUP_SIZE_BYTES,
    val maxBlobCount: Int = 64,
)

/** Why a listed blob was not staged. */
enum class BlobRejection { HASH_MISMATCH, SIZE_MISMATCH }

data class RejectedBlob(val sha256: String, val reason: BlobRejection)

/** Outcome of reading a container. Every non-[Ok] result has left nothing staged. */
sealed interface ContainerRead {
    /**
     * The manifest was read and every listed blob that verified is staged under
     * [staged] (hash → file in the staging dir). The caller claims what it applies and
     * deletes the rest (BackupEngineContract).
     */
    data class Ok(
        val manifestBytes: ByteArray,
        val header: ManifestHeader,
        val staged: Map<String, File>,
        /** Listed blobs that failed hash or size — reported, never applied. */
        val rejected: List<RejectedBlob>,
        /** Listed blobs the archive did not contain. */
        val missing: List<String>,
        /** Entries without a table row (and anything outside blobs/) — dropped. */
        val unlisted: List<String>,
    ) : ContainerRead

    /** `backup.json` without a leading manifest: a pre-E5a backup ("older version"). */
    data object LegacyFormat : ContainerRead

    /** A container of a newer major version. */
    data class UnsupportedFormat(val formatVersion: String) : ContainerRead

    /** A cap was exceeded — the archive, the manifest, a blob, or the blob count. */
    data class TooLarge(val what: String) : ContainerRead

    /** Not a readable container (not a ZIP, manifest not first, manifest unreadable …). */
    data class Invalid(val reason: String) : ContainerRead
}

/** The blob changed between the hash pass and the copy pass of an export. */
class BlobChangedDuringExportException(sha256: String) :
    IllegalStateException("blob $sha256 changed while exporting")
