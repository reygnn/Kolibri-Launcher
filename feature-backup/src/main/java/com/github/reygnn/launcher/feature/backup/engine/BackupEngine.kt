package com.github.reygnn.launcher.feature.backup.engine

import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerFormat
import com.github.reygnn.launcher.feature.backup.container.ContainerLimits
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.container.ContainerManifestCodec
import com.github.reygnn.launcher.feature.backup.container.ContainerRead
import com.github.reygnn.launcher.feature.backup.container.ContainerReader
import com.github.reygnn.launcher.feature.backup.container.ContainerWriter
import com.github.reygnn.launcher.feature.backup.container.RejectedBlob
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * The shared backup engine (SPEC_NYX_REWRITE 2a): writes and reads the E5a container
 * for an app schema, which only provides sections and blobs. Streams in, streams out
 * (U1); opening and discarding SAF documents stays with the caller (U3).
 *
 * The app keeps the parts that are its own: what the sections contain, in which order it
 * applies them (U4 "most valuable store last"), and how it maps the result to its UI.
 */
class BackupEngine internal constructor(
    ioDispatcher: CoroutineDispatcher,
    private val legacyReaders: Set<LegacyFormatReader>,
    private val limits: ContainerLimits,
    /** Parent of the per-read staging dirs of [readStaged]; tests pass a temp folder. */
    private val stagingRoot: File = defaultStagingRoot(),
) {

    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        legacyReaders: Set<@JvmSuppressWildcards LegacyFormatReader>,
    ) : this(ioDispatcher, legacyReaders, ContainerLimits())

    private val writer = ContainerWriter(ioDispatcher)
    private val reader = ContainerReader(ioDispatcher, limits)

    /**
     * Writes a backup to [output]. [sections] receives the hash of each of [blobs] (same
     * order) so a section can reference its blobs; equal content is stored once.
     */
    suspend fun export(
        output: OutputStream,
        producer: ContainerManifest.Producer,
        schemaVersion: Int,
        blobs: List<BlobSource>,
        sections: (sourceHashes: List<String>) -> Map<String, ContainerManifest.Section>,
    ): ContainerManifest {
        var written: ContainerManifest? = null
        writer.write(output, blobs) { hashes, table ->
            val manifest = ContainerManifest(
                formatVersion = ContainerFormat.CURRENT_VERSION,
                producer = producer,
                schemaVersion = schemaVersion,
                blobs = table.map { ContainerManifest.Blob(it.sha256, it.size, it.mediaType) },
                sections = sections(hashes),
            )
            written = manifest
            ContainerManifestCodec.encode(manifest)
        }
        return checkNotNull(written)
    }

    /**
     * Reads the backup behind [open] for app [appId]. [open] may be called twice (the
     * legacy path re-reads the archive). On [BackupRead.Ok] the caller owns the staged
     * blobs and must close them; every other outcome has left nothing staged.
     *
     * @param knownSections the section ids this app version understands; others are
     *   skipped by the app and reported in [BackupRead.Ok.unknownSections].
     */
    suspend fun read(
        open: () -> InputStream,
        stagingDir: File,
        appId: String,
        knownSections: Set<String>,
    ): BackupRead {
        val container = open().use { reader.read(it, stagingDir, ContainerManifestCodec::header) }
        return when (container) {
            is ContainerRead.Ok -> fromContainer(container, appId, knownSections)
            ContainerRead.LegacyFormat -> fromLegacy(open, stagingDir, appId, knownSections)
            is ContainerRead.UnsupportedFormat -> BackupRead.UnsupportedFormat(container.formatVersion)
            is ContainerRead.TooLarge -> BackupRead.TooLarge(container.what)
            is ContainerRead.Invalid -> BackupRead.Invalid(container.reason)
        }
    }

    /**
     * [read] with the frame every app needs around it (2b-0): a fresh staging dir under
     * [stagingRoot], deleted afterwards on every path, also on failure and cancellation;
     * on [BackupRead.Ok] the staged blobs are closed after [onRead], so whatever it did not
     * claim is gone. [onRead] gets the staging dir as a place to claim blobs into.
     *
     * @param kind names the staging dir (`<appId>-backup-<kind>-<nanos>`), e.g. "import".
     * @param declaredSize the document's size as the platform reports it, or
     *   [UNKNOWN_SIZE]. Above the archive cap the backup is [BackupRead.TooLarge] without
     *   a single byte read; the reader's own cap still bounds a size that was unknown or
     *   wrong.
     */
    suspend fun <T> readStaged(
        open: () -> InputStream,
        appId: String,
        knownSections: Set<String>,
        kind: String,
        declaredSize: Long = UNKNOWN_SIZE,
        onRead: suspend (read: BackupRead, stagingDir: File) -> T,
    ): T {
        val staging = File(stagingRoot, "$appId-backup-$kind-${System.nanoTime()}")
        try {
            if (declaredSize > limits.maxArchiveBytes) {
                return onRead(BackupRead.TooLarge(ContainerFormat.CAP_ARCHIVE), staging)
            }
            val read = read(open, staging, appId, knownSections)
            return if (read is BackupRead.Ok) read.blobs.use { onRead(read, staging) } else onRead(read, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun fromContainer(container: ContainerRead.Ok, appId: String, known: Set<String>): BackupRead {
        val staged = StagedBlobs(container.staged)
        val manifest = ContainerManifestCodec.decode(container.manifestBytes)
        if (manifest == null) {
            staged.close()
            return BackupRead.Invalid("manifest unreadable")
        }
        if (manifest.producer.appId != appId) {
            staged.close() // a partial import of another app's backup is never attempted
            return BackupRead.ForeignApp(manifest.producer.appId)
        }
        return BackupRead.Ok(
            manifest = manifest,
            blobs = staged,
            rejected = container.rejected,
            missing = container.missing,
            unknownSections = manifest.sections.keys - known,
            fromLegacyFormat = false,
        )
    }

    private suspend fun fromLegacy(
        open: () -> InputStream,
        stagingDir: File,
        appId: String,
        known: Set<String>,
    ): BackupRead {
        for (legacy in legacyReaders) {
            if (legacy.appId != appId) continue
            val conversion = legacy.read(open, stagingDir) ?: continue
            val manifest = ContainerManifest(
                formatVersion = ContainerFormat.CURRENT_VERSION,
                producer = conversion.producer,
                schemaVersion = conversion.schemaVersion,
                blobs = emptyList(), // staged blobs were verified by the reader
                sections = conversion.sections,
            )
            return BackupRead.Ok(
                manifest = manifest,
                blobs = StagedBlobs(conversion.staged),
                rejected = conversion.rejected,
                missing = emptyList(),
                unknownSections = conversion.sections.keys - known,
                fromLegacyFormat = true,
            )
        }
        return BackupRead.OutdatedFormat
    }
}

/** [BackupEngine.readStaged]: the platform could not tell the document's size. */
const val UNKNOWN_SIZE = -1L

/**
 * Staging lives under java.io.tmpdir: on Android the framework points it at the app's
 * cache dir when the process starts, on the JVM it is the normal temp dir. No Context
 * needed, so a strictly mocked Context in app tests sees no extra call.
 */
private fun defaultStagingRoot() = File(System.getProperty("java.io.tmpdir"))

/** Outcome of [BackupEngine.read]. Only [Ok] leaves staged blobs, owned by the caller. */
sealed interface BackupRead {
    data class Ok(
        val manifest: ContainerManifest,
        val blobs: StagedBlobs,
        /** Listed blobs that failed hash or size — reported, never applied. */
        val rejected: List<RejectedBlob>,
        /** Listed blobs the archive did not contain. */
        val missing: List<String>,
        /** Sections this app version does not know — skipped and reported. */
        val unknownSections: Set<String>,
        /** Converted from a pre-E5a archive by a [LegacyFormatReader]. */
        val fromLegacyFormat: Boolean,
    ) : BackupRead

    /** A pre-E5a backup and no reader for it (Nyx, or Kolibri after the sunset). */
    data object OutdatedFormat : BackupRead

    /** A backup written by another app — refused with a clear message, never partially applied. */
    data class ForeignApp(val appId: String) : BackupRead

    data class UnsupportedFormat(val formatVersion: String) : BackupRead
    data class TooLarge(val what: String) : BackupRead
    data class Invalid(val reason: String) : BackupRead
}
