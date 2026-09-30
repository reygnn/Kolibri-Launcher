package com.github.reygnn.kolibri_launcher.data
import android.provider.DocumentsContract
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.CappedInputStream
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.BackupException
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.launcher.feature.backup.engine.BackupRead
import com.github.reygnn.launcher.feature.backup.engine.StagedBlobs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/*
 * =============================================================================
 *               BackupRepositoryImpl — Architecture Notes
 * =============================================================================
 *
 * The original 1,444-line monolith has been split. Three classes now share
 * what used to be one:
 *
 *   - [BackupSerializer] — pure-logic JSON/strict-parser layer. No
 *     repositories, no Context, no I/O. Trivially JVM-testable.
 *   - [BackupDataAssembler] — repository composition. Reads the 8
 *     repositories to build a [BackupData], applies a [BackupData] back
 *     across them via the 10-phase import. Wallpaper file restoration is
 *     delegated to a [WallpaperRestorer] callback to keep the assembler's
 *     dependencies pure-repository.
 *   - This file — public API surface, ZIP file format (read/write/extract),
 *     URI/scheme validation, size caps, and the [WallpaperRestorer]
 *     implementation that uses Context + WallpaperFileManager to write
 *     wallpaper bytes to internal storage during import.
 *
 *
 * Why this split, after the original file argued against splitting
 * ----------------------------------------------------------------
 * The original file-header rejected a different split — Exporter /
 * Importer / ZipFormat — and the rejection was correct: that split would
 * have *duplicated* the 8 repository dependencies across two classes
 * (Exporter reads them all, Importer writes them all), without any
 * isolation benefit.
 *
 * The current split is along a different axis: instead of
 *    "export-vs-import-vs-format"
 * it splits into
 *    "pure-data-vs-repo-composition-vs-android-runtime".
 *
 * Each layer has exactly one kind of dependency:
 *    Serializer:     none
 *    Assembler:      repositories only
 *    RepositoryImpl: Android-runtime only
 *
 * No layer needs another layer's dependencies. The 8 repositories live
 * once, in the Assembler. Context + WallpaperFileManager live once, here.
 * The duplication argument that defeated the export/import split does not
 * apply.
 *
 *
 * == BACKUP FORMAT (unchanged from the monolith) ==
 * Export: always as a ZIP archive with embedded wallpaper images.
 * Import: auto-detects ZIP (current) and JSON (legacy).
 *
 * ZIP layout:
 *   ├── backup.json     — settings + per-layer metadata
 *   └── wallpapers/
 *       ├── layer_0.img — bytes for layer 0
 *       ├── layer_1.img — bytes for layer 1
 *       └── ...
 *
 * Hardening (delegated to [BackupSerializer] now, but still active):
 *   - OOM protection via file-size pre-check before reading
 *   - Type-confusion protection via [BackupSerializer.parseBackupData]
 *   - Integer-overflow handling for ARGB color fields
 *   - Float-Infinity/NaN rejection in the type validator
 * =============================================================================
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val assembler: BackupDataAssembler,
    private val serializer: BackupSerializer,
    private val wallpaperFileManager: WallpaperFileManager,
    @param:ApplicationContext private val context: Context,
    private val engine: BackupEngine,
) : BackupRepository {

    /**
     * Wallpaper file restoration callback passed to the [BackupDataAssembler]
     * during import. Pulled out as an inline implementation rather than a
     * separate class because it depends on the same Context +
     * WallpaperFileManager that the ZIP I/O paths in this file already use.
     */
    private val wallpaperRestorer = object : WallpaperRestorer {
        override suspend fun restoreFromBackup(settings: LauncherSettings) =
            this@BackupRepositoryImpl.restoreWallpaperFromBackup(settings)
    }

    // ===========================================
    // PUBLIC API: EXPORT
    // ===========================================

    override suspend fun exportToJson(): String {
        return try {
            serializer.encodeToJsonString(assembler.buildBackupData())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): JSON
            // encoding allocates memory proportional to the assembled
            // BackupData size — large favorites / custom-names maps can
            // OOM here. OOM extends Error → Throwable, not Exception.
            TimberWrapper.silentError(e, "Error exporting backup")
            throw BackupException("Export failed", e)
        }
    }

    // ===========================================
    // PUBLIC API: IMPORT (JSON)
    // ===========================================

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        return try {
            val backup = serializer.parseBackupData(jsonString)
                ?: return ImportResult.InvalidFormat

            if (options.importNothing) {
                return ImportResult.Error("No import options selected")
            }

            if (!serializer.isVersionSupported(backup.version)) {
                return ImportResult.UnsupportedVersion(backup.version)
            }

            assembler.performImport(backup, options, wallpaperRestorer)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): JSON
            // parse + import allocates significant memory; OOM during
            // parseBackupData on adversarial input or during repository
            // writes is realistic. OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Error importing backup")
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    // ===========================================
    // ZIP FORMAT DETECTION
    // ===========================================

    // ===========================================
    // ZIP EXPORT
    // ===========================================

    /**
     * Resolves a URI string to a local file. Works only for `file://` URIs
     * (internal wallpaper files).
     */
    private fun resolveToLocalFile(uriString: String): File? {
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == "file") {
                uri.path?.let { File(it) }
            } else {
                null
            }
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
            null
        }
    }

    // ===========================================
    // ZIP IMPORT
    // ===========================================

    /**
     * Imports from a ZIP backup.
     *
     * 1. Extracts `backup.json` and wallpaper images
     * 2. Saves images to internal storage via [WallpaperFileManager]
     * 3. Resolves `imageFileName` references to internal URIs
     * 4. Performs the standard import path
     *
     * Every extracted image the restored wallpaper does not claim is deleted afterwards, on
     * every path (aborted extraction, invalid/unsupported backup, failed import, dropped layer,
     * cancellation), so an import never leaves orphans in internal storage until the next
     * cold-start gcOrphans sweep (port of nyx §Audit-3 A3-05).
     */
    private suspend fun importFromZip(uri: Uri, options: ImportOptions): ImportResult {
        val extractedImages = mutableMapOf<String, String>() // zipEntryName → internal URI string
        // Claimed by the wallpaper restore BEFORE it saves (see restoreWallpaperFromBackup).
        val claimedImages = HashSet<String>()
        return try {
            extractAndImportZip(uri, options, extractedImages, claimedImages)
        } finally {
            (extractedImages.values - claimedImages).forEach { wallpaperFileManager.deleteFile(it) }
        }
    }

    private suspend fun extractAndImportZip(
        uri: Uri,
        options: ImportOptions,
        extractedImages: MutableMap<String, String>,
        claimedImages: MutableSet<String>,
    ): ImportResult {
        var jsonString: String? = null
        var imageEntryCount = 0

        // 1. Extract ZIP
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Bound the whole compressed archive: a streaming provider can report statSize == -1
                // and slip past the size gate in loadBackupFromFile, so cap the bytes ZipInputStream
                // may pull (extraction AND closeEntry skips) — a decompression bomb's work stays
                // bounded. Shared CappedInputStream, mirroring nyx's backup import (§Audit-2 N5).
                val boundedInput = CappedInputStream(
                    BufferedInputStream(inputStream),
                    AppConstants.MAX_BACKUP_SIZE_BYTES + 1,
                )
                ZipInputStream(boundedInput).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "backup.json" -> {
                                jsonString = zipIn.readBytes().toString(Charsets.UTF_8)
                            }
                            // Extract only when the wallpaper is actually being restored — else
                            // nothing would ever reference the blobs (nyx parity).
                            options.importWallpaper && entry.name.startsWith("wallpapers/") && !entry.isDirectory -> {
                                // Reject an archive spamming the wallpaper dir before it can.
                                if (++imageEntryCount > MAX_IMAGE_ENTRIES) {
                                    return ImportResult.Error("Backup archive has too many images")
                                }
                                // Cap each blob's DECOMPRESSED size so a bomb entry can't fill the
                                // disk: extract through a per-blob CappedInputStream and reject
                                // (dropping the partial file) if it exceeds the limit.
                                val cappedBlob = CappedInputStream(zipIn, AppConstants.MAX_BACKUP_SIZE_BYTES + 1)
                                val internalUri = wallpaperFileManager.copyFromInputStream(cappedBlob)
                                if (cappedBlob.limitReached) {
                                    internalUri?.let { wallpaperFileManager.deleteFile(it) }
                                    return ImportResult.Error("Backup image is too large")
                                }
                                if (internalUri != null) {
                                    extractedImages[entry.name] = internalUri.toString()
                                    Timber.d("Extracted ${entry.name} → $internalUri")
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                    if (boundedInput.limitReached) return ImportResult.Error("Backup file is too large")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error extracting ZIP backup")
            return ImportResult.Error("Failed to extract backup archive")
        }

        val jsonContent = jsonString
        if (jsonContent.isNullOrBlank()) {
            TimberWrapper.silentError("ZIP backup does not contain backup.json")
            return ImportResult.InvalidFormat
        }

        // 2. Parse JSON via serializer
        val backup = serializer.parseBackupData(jsonContent)
            ?: return ImportResult.InvalidFormat

        if (options.importNothing) return ImportResult.Error("No import options selected")
        if (!serializer.isVersionSupported(backup.version)) {
            return ImportResult.UnsupportedVersion(backup.version)
        }

        // 3. Resolve imageFileName → internal URI
        val resolvedBackup = serializer.resolveZipImages(backup, extractedImages)

        // 4. Standard import
        Timber.i("ZIP import: ${extractedImages.size} images extracted, starting import")
        // Per-import restorer so the claimed set stays local to THIS import (the class is a
        // @Singleton; a shared field would race between overlapping imports).
        val zipWallpaperRestorer = object : WallpaperRestorer {
            override suspend fun restoreFromBackup(settings: LauncherSettings) =
                restoreWallpaperFromBackup(settings, onClaim = claimedImages::addAll)
        }
        return try {
            assembler.performImport(resolvedBackup, options, zipWallpaperRestorer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): bitmap
            // copying during wallpaper restore + multi-repo writes are
            // memory-heavy. OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Error importing ZIP backup")
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Reads only `backup.json` from a ZIP archive (for preview).
     */
    private fun readJsonFromZip(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name == "backup.json") {
                            return@use zipIn.readBytes().toString(Charsets.UTF_8)
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                    null
                }
            }
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
            TimberWrapper.silentError(e, "Error reading JSON from ZIP")
            null
        }
    }

    // ===========================================
    // WALLPAPER RESTORE (file-system side; called by Assembler)
    // ===========================================

    /**
     * @param onClaim receives the internal image URIs of the state about to be saved, BEFORE the
     *   save is attempted: DataStore can commit the write and the call still end in a
     *   CancellationException, so claiming afterwards could let the ZIP import delete files the
     *   persisted state already references. Failing safe leaves at most an orphan for gcOrphans.
     * @return number of wallpaper layers that could not be restored (0 = clean).
     */
    private suspend fun restoreWallpaperFromBackup(
        settings: LauncherSettings,
        onClaim: (Collection<String>) -> Unit = {},
    ): Int {
        return if (settings.wallpaperLayers.isNotEmpty()) {
            importMultiLayerWallpaper(settings.wallpaperLayers, onClaim)
        } else {
            importSingleLayerWallpaper(settings, onClaim)
        }
    }

    /** @return number of layers that were present but could not be restored. */
    private suspend fun importMultiLayerWallpaper(
        layerBackups: List<WallpaperLayerBackup>,
        onClaim: (Collection<String>) -> Unit,
    ): Int {
        val validLayerStates = mutableListOf<WallpaperLayerState>()

        for ((index, layerBackup) in layerBackups.withIndex()) {
            val uriString = layerBackup.imageUri
            if (uriString.isNullOrBlank()) {
                Timber.w("Wallpaper layer $index has no URI, skipping")
                continue
            }

            try {
                val sourceUri = uriString.toUri()
                val canAccess = try {
                    context.contentResolver.openInputStream(sourceUri)?.use { true } ?: false
                } catch (e: Exception) {
                    // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
                    // Exception sufficient (pure I/O probe, no allocation path → no Error).
                    false
                }

                if (canAccess) {
                    val internalUri = wallpaperFileManager.copyToInternal(sourceUri)
                    if (internalUri != null) {
                        validLayerStates.add(
                            layerBackup.toLayerState().copy(imageUri = internalUri.toString())
                        )
                    } else {
                        Timber.w("Failed to copy layer $index to internal storage, skipping")
                    }
                } else {
                    Timber.w("Wallpaper layer $index URI not accessible, skipping: $uriString")
                }
            } catch (e: CancellationException) {
                // Rethrow: copyToInternal suspends, so a cancelled restore
                // would otherwise file one bogus report per remaining layer
                // AND keep copying in a dead job. Cancellation aborts the
                // whole import loop, as it should.
                throw e
            } catch (e: Throwable) {
                // Catch kept (Expected error, four-category frame): per-layer
                // bitmap copy can OOM on a large source bitmap; one bad layer
                // must not abort the rest of the import. OOM extends Error →
                // Throwable.
                TimberWrapper.silentError(e, "Failed to validate wallpaper layer $index URI")
            }
        }

        if (validLayerStates.isNotEmpty()) {
            val wallpaperState = WallpaperState.multiLayer(validLayerStates)
            onClaim(validLayerStates.mapNotNull { it.imageUri })
            assembler.saveWallpaperStateForRestore(wallpaperState)
            Timber.i("Imported ${validLayerStates.size}/${layerBackups.size} wallpaper layers")
        } else {
            Timber.w("No valid wallpaper layers found, wallpaper not restored")
        }
        // Only layers that actually referenced an image but failed to restore
        // count as "dropped" — a metadata-only layer (blank imageUri) never had
        // an image, so it must not trigger the "image no longer available"
        // warning. Every entry in validLayerStates came from a non-blank URI,
        // so this difference is exactly the image-bearing layers that failed.
        val layersWithImage = layerBackups.count { !it.imageUri.isNullOrBlank() }
        return layersWithImage - validLayerStates.size
    }

    /**
     * Backward-compat import path (kept per the refactor decision): an OLD backup
     * of a single-image wallpaper encodes it only in the flat `wallpaperUri`/`Scale`
     * /`TranslateX`/`TranslateY` fields with an empty `wallpaperLayers` array. This
     * reads those flat fields and reconstructs the canonical one-element layer
     * state. New backups always carry `wallpaperLayers`, so they never reach here.
     *
     * @return 1 if a wallpaper was present but could not be restored, else 0.
     */
    private suspend fun importSingleLayerWallpaper(
        settings: LauncherSettings,
        onClaim: (Collection<String>) -> Unit,
    ): Int {
        val wallpaperUri = settings.wallpaperUri
        if (wallpaperUri.isNullOrBlank()) return 0

        try {
            val sourceUri = wallpaperUri.toUri()
            val canAccess = try {
                context.contentResolver.openInputStream(sourceUri)?.use { true } ?: false
            } catch (e: Exception) {
                // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
                // Exception sufficient (pure I/O probe, no allocation path → no Error).
                false
            }

            if (canAccess) {
                val internalUri = wallpaperFileManager.copyToInternal(sourceUri)
                if (internalUri != null) {
                    val wallpaperState = WallpaperState.single(
                        uri = internalUri.toString(),
                        scale = settings.wallpaperScale ?: 1.0f,
                        translateX = settings.wallpaperTranslateX ?: 0.0f,
                        translateY = settings.wallpaperTranslateY ?: 0.0f,
                    )
                    onClaim(listOf(internalUri.toString()))
                    assembler.saveWallpaperStateForRestore(wallpaperState)
                    Timber.i("Imported wallpaper settings (legacy flat single-layer backup)")
                    return 0
                } else {
                    Timber.w("Failed to copy wallpaper to internal storage")
                }
            } else {
                Timber.w("Wallpaper URI not accessible, skipping: $wallpaperUri")
            }
        } catch (e: CancellationException) {
            // Rethrow: copyToInternal and saveWallpaperStateForRestore both
            // suspend, so a cancelled restore must propagate rather than be
            // logged as a failed wallpaper restore.
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): single-layer
            // bitmap copy via WallpaperFileManager.copyToInternal can OOM on
            // large source bitmap. OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Failed to restore wallpaper")
        }
        // A wallpaper was present in the backup but none of the paths above
        // restored it (inaccessible URI, copy failure, or OOM).
        return 1
    }

    // ===========================================
    // PUBLIC API: FILE I/O — SAVE
    // ===========================================

    override suspend fun saveBackupToFile(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                TimberWrapper.silentError("Empty URI string provided")
                throw BackupException("Invalid file location")
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                TimberWrapper.silentError(e, "Invalid URI format: $uriString")
                throw BackupException("Invalid file location format", e)
            }

            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf(AppConstants.SCHEME_CONTENT, AppConstants.SCHEME_FILE)) {
                TimberWrapper.silentError("Unsupported URI scheme: $scheme")
                throw BackupException("Unsupported file location type")
            }

            val backupData = assembler.buildBackupData()
            writeOrDiscard(uri) { output -> exportContainer(output, backupData) }

            Timber.i("Backup saved to: $uri")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            TimberWrapper.silentError(e, "Permission denied for URI")
            throw BackupException("No permission to write to this location", e)
        } catch (e: IOException) {
            TimberWrapper.silentError(e, "I/O error while saving backup")
            throw BackupException("Failed to write file (storage full or unavailable?)", e)
        } catch (e: Throwable) {
            // Umbrella catch widened from Exception per four-category frame:
            // writeZipBackup allocates memory for JSON encoding + ZIP buffers
            // proportional to backup + embedded wallpaper sizes. Large multi-
            // layer 4K wallpapers can OOM. OOM extends Error → Throwable, was
            // missed by the previous `catch (e: Exception)` umbrella.
            TimberWrapper.silentError(e, "Unexpected error saving backup")
            throw BackupException("Failed to save backup: ${e.message}", e)
        }
    }

    // ===========================================
    // PUBLIC API: FILE I/O — LOAD
    // ===========================================

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) return@withContext ImportResult.Error("Invalid file location")

            val uri = try {
                uriString.toUri()
            } catch (e: Exception) {
                // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
                // Exception sufficient (URI parse, no allocation path → no Error).
                return@withContext ImportResult.Error("Invalid format")
            }

            // OOM protection: check file size before reading
            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, AppConstants.MODE_READ_ONLY)?.use { pfd ->
                    pfd.statSize
                } ?: 0L
            } catch (e: Exception) {
                // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
                // Exception sufficient (pure I/O probe, no allocation path → no Error).
                Timber.w(e, "Could not determine file size, proceeding with caution")
                0L
            }

            if (fileSize > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                TimberWrapper.silentError("File too large: $fileSize bytes (max: ${AppConstants.MAX_BACKUP_SIZE_BYTES})")
                return@withContext ImportResult.Error("Backup file is too large (>${AppConstants.MAX_BACKUP_SIZE_BYTES / 1024 / 1024}MB)")
            }

            // Format detection: ZIP or JSON?
            if (options.importNothing) return@withContext ImportResult.Error("No import options selected")
            val staging = stagingDir("import")
            try {
                when (val read = engine.read({ openInput(uri) }, staging, APP_ID, KNOWN_SECTIONS)) {
                    is BackupRead.Ok -> read.blobs.use { blobs -> importContainer(read, blobs, staging, options) }
                    // A pre-E5a Kolibri archive: the old ZIP reader below still imports it until
                    // :kolibri:backup-legacy takes over (2a-6). Unzipped legacy JSON is no longer
                    // read (every valid backup is zipped — SPEC_NYX_REWRITE E5a).
                    BackupRead.OutdatedFormat -> importFromZip(uri, options)
                    is BackupRead.ForeignApp -> ImportResult.Error("Backup was made by another app (${read.appId})")
                    is BackupRead.UnsupportedFormat -> ImportResult.UnsupportedVersion(read.formatVersion)
                    is BackupRead.TooLarge -> ImportResult.Error("Backup file is too large")
                    is BackupRead.Invalid -> ImportResult.InvalidFormat
                }
            } finally {
                staging.deleteRecursively()
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Umbrella catch widened from Exception per four-category frame:
            // file read + JSON parse + import is the OOM-prone path the
            // MAX_BACKUP_SIZE_BYTES cap mitigates but doesn't eliminate (a
            // backup at exactly the cap can still OOM during parse on a
            // memory-tight device). OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Error loading backup")
            ImportResult.Error("Failed to load backup: ${e.message}")
        }
    }

    // ===========================================
    // PUBLIC API: FILE I/O — PREVIEW
    // ===========================================

    override suspend fun previewBackup(uriString: String): BackupPreview? = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                TimberWrapper.silentError("Empty URI string provided for preview")
                return@withContext null
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                TimberWrapper.silentError(e, "Invalid URI format for preview: $uriString")
                return@withContext null
            }

            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf(AppConstants.SCHEME_CONTENT, AppConstants.SCHEME_FILE)) {
                TimberWrapper.silentError("Unsupported URI scheme for preview: $scheme")
                return@withContext null
            }

            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize
                } ?: 0L
            } catch (e: Exception) {
                // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
                // Exception sufficient (pure I/O probe, no allocation path → no Error).
                Timber.w(e, "Could not determine file size for preview")
                0L
            }

            val staging = stagingDir("preview")
            try {
                when (val read = engine.read({ openInput(uri) }, staging, APP_ID, KNOWN_SECTIONS)) {
                    is BackupRead.Ok -> read.blobs.use {
                        val settings = read.manifest.sections[SECTION_BACKUP]
                            ?.let { section -> serializer.settingsFromJson(section.data) }
                            ?: return@withContext null
                        serializer.buildPreview(backupDataOf(read.manifest.producer, settings))
                    }
                    BackupRead.OutdatedFormat -> legacyPreview(uri) // until 2a-6
                    else -> null
                }
            } finally {
                staging.deleteRecursively()
            }

        } catch (e: SecurityException) {
            TimberWrapper.silentError(e, "Permission denied for preview")
            null
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous I/O only (AUDIT-12 whitelist review).
            // Umbrella catch widened from Exception per four-category frame:
            // preview path reads the JSON content and parses it; OOM during
            // JSONObject construction or parseBackupData on a large input can
            // still happen even with MAX_PREVIEW_SIZE_BYTES — the cap protects
            // the read, not subsequent in-memory parsing. OOM extends Error →
            // Throwable.
            TimberWrapper.silentError(e, "Unexpected error while creating preview")
            null
        }
    }

    // ===========================================
    // CONTAINER FORMAT (SPEC_NYX_REWRITE E5a, 2a-5)
    // ===========================================

    /**
     * Writes [backupData] as an E5a container. The settings go into one versioned section;
     * every wallpaper image that is a local file becomes a blob, referenced from its layer
     * by hash in `imageFileName` (the blob, not a path, is the source of truth — the layer's
     * `imageUri` is dropped). Content-URI layers are not embeddable and stay as they are.
     */
    private suspend fun exportContainer(output: OutputStream, backupData: BackupData) {
        val settings = backupData.settings
        val sources = ArrayList<BlobSource>()
        fun blobOf(file: File): Int {
            sources += BlobSource(IMAGE_MEDIA_TYPE) { file.inputStream() }
            return sources.size - 1
        }
        val layerSource = settings.wallpaperLayers.map { layer ->
            layer.imageUri?.let(::resolveToLocalFile)?.takeIf { it.exists() }?.let(::blobOf)
        }
        val singleSource = if (settings.wallpaperLayers.isEmpty()) {
            settings.wallpaperUri?.let(::resolveToLocalFile)?.takeIf { it.exists() }?.let(::blobOf)
        } else {
            null
        }
        engine.export(
            output = output,
            producer = ContainerManifest.Producer(APP_ID, backupData.appVersion, backupData.timestamp),
            schemaVersion = SCHEMA_VERSION,
            blobs = sources,
        ) { hashes ->
            val layers = settings.wallpaperLayers.mapIndexed { index, layer ->
                val source = layerSource[index]
                if (source != null) layer.copy(imageUri = null, imageFileName = hashes[source]) else layer.copy(imageFileName = null)
            }
            val single = singleSource?.let { hashes[it] } ?: layers.firstNotNullOfOrNull { it.imageFileName }
            val sectionSettings = settings.copy(wallpaperLayers = layers, wallpaperImageFileName = single)
            mapOf(SECTION_BACKUP to ContainerManifest.Section(SECTION_VERSION, serializer.settingsToJson(sectionSettings)))
        }
        Timber.i("Backup written: ${sources.size} image source(s)")
    }

    /**
     * Imports a read container: each referenced blob becomes an internal wallpaper file,
     * then the unchanged resolve + restore path runs (the blob hash plays the role the ZIP
     * entry name had). Files that no layer ends up claiming are deleted again.
     */
    private suspend fun importContainer(
        read: BackupRead.Ok,
        blobs: StagedBlobs,
        staging: File,
        options: ImportOptions,
    ): ImportResult {
        val section = read.manifest.sections[SECTION_BACKUP] ?: return ImportResult.InvalidFormat
        val settings = serializer.settingsFromJson(section.data) ?: return ImportResult.InvalidFormat
        val backup = backupDataOf(read.manifest.producer, settings)
        val extracted = mutableMapOf<String, String>() // blob hash → internal wallpaper URI
        val claimed = HashSet<String>()
        try {
            if (options.importWallpaper) {
                val referenced = settings.wallpaperLayers.mapNotNull { it.imageFileName } +
                    listOfNotNull(settings.wallpaperImageFileName)
                for (hash in referenced.toSet()) {
                    val file = blobs.claim(hash, File(staging, "claimed-$hash")) ?: continue
                    val internal = file.inputStream().use { wallpaperFileManager.copyFromInputStream(it) }
                    file.delete()
                    if (internal != null) extracted[hash] = internal.toString()
                }
            }
            val resolved = serializer.resolveZipImages(backup, extracted)
            val restorer = object : WallpaperRestorer {
                override suspend fun restoreFromBackup(settings: LauncherSettings) =
                    restoreWallpaperFromBackup(settings, onClaim = claimed::addAll)
            }
            val result = assembler.performImport(resolved, options, restorer)
            // A layer whose blob was rejected (hash/size) or missing never reaches the restorer:
            // it is reported as dropped, not silently lost (B9).
            val unresolved = if (options.importWallpaper) {
                settings.wallpaperLayers.count { layer -> layer.imageFileName?.let { it !in extracted } == true }
            } else {
                0
            }
            return if (result is ImportResult.Success && unresolved > 0) {
                result.copy(droppedWallpaperLayers = result.droppedWallpaperLayers + unresolved)
            } else {
                result
            }
        } finally {
            (extracted.values - claimed).forEach { wallpaperFileManager.deleteFile(it) }
        }
    }

    private fun backupDataOf(producer: ContainerManifest.Producer, settings: LauncherSettings) = BackupData(
        version = AppConstants.BACKUP_VERSION,
        timestamp = producer.createdAtEpochMillis,
        appVersion = producer.appVersion,
        settings = settings,
    )

    /**
     * A fresh staging dir per import/preview, under java.io.tmpdir — on Android the framework
     * points that at the app's cache dir when the process starts; on the JVM (tests with a
     * mocked Context) it is the normal temp dir. Deleted by the caller in `finally`.
     */
    private fun stagingDir(kind: String) =
        File(System.getProperty("java.io.tmpdir"), "kolibri-backup-$kind-${System.nanoTime()}")

    private fun openInput(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read from selected location")

    /** Preview of a pre-E5a archive through the old reader — until 2a-6. */
    private fun legacyPreview(uri: Uri): BackupPreview? {
        val json = readJsonFromZip(uri) ?: return null
        val backup = serializer.parseBackupData(json) ?: return null
        return serializer.buildPreview(backup)
    }

    /**
     * U3: an export writes into a document the user has just created. If writing fails or
     * is cancelled, the document is deleted — no half, unimportable ZIP is left behind.
     */
    private suspend fun writeOrDiscard(uri: Uri, write: suspend (OutputStream) -> Unit) {
        var complete = false
        try {
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw BackupException("Cannot write to selected location")
            output.use { write(it) }
            complete = true
        } finally {
            if (!complete) discardDocument(uri)
        }
    }

    private fun discardDocument(uri: Uri) {
        try {
            if (uri.scheme == AppConstants.SCHEME_FILE) {
                uri.path?.let { File(it).delete() }
            } else {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            // no suspension point; Exception sufficient — a best-effort delete of the half-written
            // document; the export's own failure is what the user is told about.
            Timber.w(e, "Could not delete the incomplete backup document")
        }
    }

    private companion object {
        // A real backup carries one image per wallpaper layer; reject an archive with absurdly
        // many image entries before it can spam internal storage (§Audit-2 N5 mirror).
        const val MAX_IMAGE_ENTRIES = 64

        // E5a container schema of Kolibri (2a-5): one versioned section with the settings.
        const val APP_ID = "kolibri"
        const val SCHEMA_VERSION = 1
        const val SECTION_BACKUP = "kolibri.backup"
        const val SECTION_VERSION = 1
        val KNOWN_SECTIONS = setOf(SECTION_BACKUP)
        const val IMAGE_MEDIA_TYPE = "image/*"
    }
}
