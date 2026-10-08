package com.github.reygnn.kolibri_launcher.data
import com.github.reygnn.launcher.common.data.saf.DocumentUnavailableException
import com.github.reygnn.launcher.common.data.saf.InvalidDocumentLocationException
import com.github.reygnn.launcher.common.data.saf.SafDocuments
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager

import android.content.Context
import androidx.core.net.toUri
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.BackupException
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.wallpaper.WallpaperBackupBlobs
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.launcher.feature.backup.engine.BackupRead
import com.github.reygnn.launcher.feature.backup.engine.StagedBlobs
import com.github.reygnn.launcher.feature.backup.engine.writeOrDiscard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
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
    /** The Android half of SAF documents, shared with Nyx (2b-4a). */
    private val safDocuments: SafDocuments,
    // Injected since 2a-7b: changing previewBackup's signature would otherwise have meant a new
    // A13 entry; all three file operations now use it (A13: -3).
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    /** The wallpaper half of a backup since 3a-7: collect, bind back, O2, cleanup through the store. */
    private val wallpaperBlobs: WallpaperBackupBlobs,
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
        // Every internal file this restore makes or adopts (copyToInternal, O2 copies) — the
        // container import releases the unclaimed ones on failure (audit A5, as Nyx' `copies`).
        onCopy: (String) -> Unit = {},
    ): Int {
        return if (settings.wallpaperLayers.isNotEmpty()) {
            importMultiLayerWallpaper(settings.wallpaperLayers, onClaim, onCopy)
        } else {
            importSingleLayerWallpaper(settings, onClaim, onCopy)
        }
    }

    /** @return number of layers that were present but could not be restored. */
    private suspend fun importMultiLayerWallpaper(
        layerBackups: List<WallpaperLayerBackup>,
        onClaim: (Collection<String>) -> Unit,
        onCopy: (String) -> Unit,
    ): Int {
        val validLayerStates = mutableListOf<WallpaperLayerState>()
        // Layers whose image reached internal storage, before O2 gives each its own file.
        val copied = mutableListOf<Pair<WallpaperLayerBackup, String>>()

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
                        onCopy(internalUri.toString())
                        copied += layerBackup to internalUri.toString()
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

        // O2 (3a-7, one place for both apps): the container stores equal content once, so layers of
        // the same image resolve to the same extracted file, and copyToInternal hands an internal
        // file back unchanged — a file an earlier layer already got is copied for this one.
        val ownFiles = wallpaperBlobs.assignOwnFiles(copied.map { it.second }) { uri ->
            context.contentResolver.openInputStream(uri.toUri())
        }
        ownFiles.filterNotNull().forEach(onCopy)
        copied.forEachIndexed { i, (layerBackup, _) ->
            val own = ownFiles[i]
            if (own != null) {
                validLayerStates.add(layerBackup.toLayerState().copy(imageUri = own))
            } else {
                Timber.w("Failed to give wallpaper layer ${layerBackup.id} its own file, skipping")
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
        onCopy: (String) -> Unit,
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
                    onCopy(internalUri.toString())
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

    override suspend fun saveBackupToFile(uriString: String): Boolean = withContext(ioDispatcher) {
        try {
            val uri = try {
                safDocuments.documentUri(uriString)
            } catch (e: InvalidDocumentLocationException) {
                TimberWrapper.silentError(e, "Invalid backup location: $uriString")
                throw BackupException(saveLocationMessage(e), e)
            }

            // Assembling runs inside the write: if reading a store fails, the document is discarded
            // as well (U3) instead of staying behind empty, as Nyx does (2b-3c follow-up).
            writeOrDiscard(open = { safDocuments.openOutput(uri) }, discard = { safDocuments.discard(uri) }) { output ->
                exportContainer(output, assembler.buildBackupData())
            }

            Timber.i("Backup saved to: $uri")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: DocumentUnavailableException) {
            // D3 (2b-4a): the provider gave no output stream — keeps its own visible text instead
            // of the generic I/O one below.
            TimberWrapper.silentError(e, "No output stream for the backup document")
            throw BackupException(e.message ?: "Cannot write to selected location", e)
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

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult = withContext(ioDispatcher) {
        try {
            // D1 (2b-4a): only content:// and file:// — the import used to pass any scheme to the
            // resolver; now it is refused before the resolver is asked.
            val uri = try {
                safDocuments.documentUri(uriString)
            } catch (e: InvalidDocumentLocationException) {
                return@withContext ImportResult.Error(loadLocationMessage(e))
            }

            // OOM protection: the engine refuses a declared size above the archive cap unread.
            val fileSize = safDocuments.declaredSize(uri)

            if (options.importNothing) return@withContext ImportResult.Error("No import options selected")
            engine.readStaged(
                open = { safDocuments.openInput(uri) },
                appId = KolibriBackupSchema.APP_ID,
                knownSections = KolibriBackupSchema.KNOWN_SECTIONS,
                kind = "import",
                declaredSize = fileSize,
            ) { read, staging ->
                refusalOf(read, fileSize) ?: importContainer(read as BackupRead.Ok, read.blobs, staging, options)
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

    override suspend fun previewBackup(uriString: String): PreviewResult = withContext(ioDispatcher) {
        try {
            val uri = try {
                safDocuments.documentUri(uriString)
            } catch (e: InvalidDocumentLocationException) {
                TimberWrapper.silentError(e, "Invalid location for preview: $uriString")
                return@withContext PreviewResult.Refused(ImportResult.Error(previewLocationMessage(e)))
            }

            val fileSize = safDocuments.declaredSize(uri)
            engine.readStaged(
                open = { safDocuments.openInput(uri) },
                appId = KolibriBackupSchema.APP_ID,
                knownSections = KolibriBackupSchema.KNOWN_SECTIONS,
                kind = "preview",
                declaredSize = fileSize,
            ) { read, _ ->
                // 2a-7b: a refusal names its reason, mapped exactly like the import's.
                refusalOf(read, fileSize)?.let { PreviewResult.Refused(it) }
                    ?: previewOf(read as BackupRead.Ok)
            }
        } catch (e: CancellationException) {
            throw e // readStaged suspends; a cancellation must propagate, never become a refusal
        } catch (e: SecurityException) {
            TimberWrapper.silentError(e, "Permission denied for preview")
            PreviewResult.Refused(ImportResult.Error("Permission denied"))
        } catch (e: Throwable) {
            // Umbrella catch widened from Exception per four-category frame: the preview reads
            // the whole container and parses it; OOM during parsing can still happen even with
            // the archive cap — the cap protects the read, not the in-memory parse. OOM extends
            // Error → Throwable.
            TimberWrapper.silentError(e, "Unexpected error while creating preview")
            PreviewResult.Refused(ImportResult.Error("Failed to load backup: ${e.message}"))
        }
    }

    /**
     * The one mapping of a refused engine outcome, shared by import and preview (2a-7b) so the
     * two can never disagree on the reason. Null for [BackupRead.Ok] — not a refusal.
     */
    private fun refusalOf(read: BackupRead, declaredSize: Long): ImportResult? = when (read) {
        is BackupRead.Ok -> null
        // A pre-E5a archive reaches this only when no LegacyFormatReader is bound — after the
        // sunset of :kolibri:backup-legacy (2a-6). While the module is there, it up-converts old
        // archives and they arrive as BackupRead.Ok.
        BackupRead.OutdatedFormat -> ImportResult.OutdatedBackup
        is BackupRead.ForeignApp -> ImportResult.ForeignBackup(read.appId)
        is BackupRead.UnsupportedFormat -> ImportResult.UnsupportedVersion(read.formatVersion)
        is BackupRead.TooLarge -> tooLarge(declaredSize)
        is BackupRead.Invalid -> ImportResult.InvalidFormat
    }

    /** The section of a readable container, or null when it is missing or can't be decoded. */
    private fun settingsOf(read: BackupRead.Ok): LauncherSettings? =
        read.manifest.sections[KolibriBackupSchema.SECTION_BACKUP]?.let { section -> serializer.settingsFromJson(section.data) }

    /** A readable container whose section does not decode is refused like its import: invalid. */
    private fun previewOf(read: BackupRead.Ok): PreviewResult {
        val settings = settingsOf(read) ?: return PreviewResult.Refused(ImportResult.InvalidFormat)
        return PreviewResult.Readable(serializer.buildPreview(backupDataOf(read.manifest.producer, settings)))
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
        // The layer images come from the shared collector (3a-7); the app turns them into blobs.
        val collected = wallpaperBlobs.collect(settings.wallpaperLayers) { uri -> resolveToLocalFile(uri)?.takeIf { it.exists() } }
        val sources = ArrayList<BlobSource>()
        collected.files.forEach { file -> sources += BlobSource(IMAGE_MEDIA_TYPE) { file.inputStream() } }
        fun blobOf(file: File): Int {
            sources += BlobSource(IMAGE_MEDIA_TYPE) { file.inputStream() }
            return sources.size - 1
        }
        val singleSource = if (settings.wallpaperLayers.isEmpty()) {
            settings.wallpaperUri?.let(::resolveToLocalFile)?.takeIf { it.exists() }?.let(::blobOf)
        } else {
            null
        }
        engine.export(
            output = output,
            producer = ContainerManifest.Producer(KolibriBackupSchema.APP_ID, backupData.appVersion, backupData.timestamp),
            schemaVersion = KolibriBackupSchema.SCHEMA_VERSION,
            blobs = sources,
        ) { hashes ->
            val layers = collected.rebind(settings.wallpaperLayers, hashes)
            val single = singleSource?.let { hashes[it] } ?: layers.firstNotNullOfOrNull { it.imageFileName }
            val sectionSettings = settings.copy(wallpaperLayers = layers, wallpaperImageFileName = single)
            mapOf(KolibriBackupSchema.SECTION_BACKUP to ContainerManifest.Section(KolibriBackupSchema.SECTION_VERSION, serializer.settingsToJson(sectionSettings)))
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
        val settings = settingsOf(read) ?: return ImportResult.InvalidFormat
        val backup = backupDataOf(read.manifest.producer, settings)
        val extracted = mutableMapOf<String, String>() // blob hash → internal wallpaper URI
        // Every other internal file the restore made or adopted (copyToInternal, O2 copies, A5).
        val copies = HashSet<String>()
        val claimed = HashSet<String>()
        try {
            if (options.importWallpaper) {
                val referenced = settings.wallpaperLayers.mapNotNull { it.imageFileName } +
                    listOfNotNull(settings.wallpaperImageFileName)
                wallpaperBlobs.extract(referenced, blobs::claim, staging, into = extracted)
            }
            val resolved = serializer.resolveZipImages(backup, extracted)
            val restorer = object : WallpaperRestorer {
                override suspend fun restoreFromBackup(settings: LauncherSettings) =
                    restoreWallpaperFromBackup(settings, onClaim = claimed::addAll, onCopy = { copies += it })
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
            // Only the UNCLAIMED copies, through the store (3a-7): claimed ones may be missing
            // from the persisted state after a silently failed save and must never be passed in.
            // Since audit A5 ALL copies, not only the extracted blobs: also what copyToInternal
            // made or adopted and the O2 copies. An adopted internal source is safe here — the
            // store deletes only what nothing persisted references (as in Nyx' import).
            wallpaperBlobs.release((extracted.values + copies) - claimed)
        }
    }

    private fun backupDataOf(producer: ContainerManifest.Producer, settings: LauncherSettings) = BackupData(
        version = AppConstants.BACKUP_VERSION,
        timestamp = producer.createdAtEpochMillis,
        appVersion = producer.appVersion,
        settings = settings,
    )

    // D2 (2b-4a): each operation keeps its own visible text for an invalid location, exactly as
    // before the location check moved to SafDocuments.

    private fun saveLocationMessage(e: InvalidDocumentLocationException) = when (e.reason) {
        InvalidDocumentLocationException.Reason.BLANK -> "Invalid file location"
        InvalidDocumentLocationException.Reason.MALFORMED -> "Invalid file location format"
        InvalidDocumentLocationException.Reason.UNSUPPORTED_SCHEME -> "Unsupported file location type"
    }

    private fun loadLocationMessage(e: InvalidDocumentLocationException) = when (e.reason) {
        InvalidDocumentLocationException.Reason.BLANK -> "Invalid file location"
        InvalidDocumentLocationException.Reason.MALFORMED -> "Invalid format"
        // New with D1: the import had no scheme check before; same text as the save path.
        InvalidDocumentLocationException.Reason.UNSUPPORTED_SCHEME -> "Unsupported file location type"
    }

    private fun previewLocationMessage(e: InvalidDocumentLocationException) = when (e.reason) {
        InvalidDocumentLocationException.Reason.BLANK -> "Invalid file location"
        InvalidDocumentLocationException.Reason.MALFORMED -> "Invalid format"
        InvalidDocumentLocationException.Reason.UNSUPPORTED_SCHEME -> "Unsupported file location type: ${e.scheme}"
    }

    /**
     * A declared size over the cap (refused by the engine unread) keeps its detailed message;
     * a cap the reader hits while streaming keeps the short one.
     */
    private fun tooLarge(declaredSize: Long): ImportResult.Error {
        if (declaredSize <= AppConstants.MAX_BACKUP_SIZE_BYTES) return ImportResult.Error("Backup file is too large")
        TimberWrapper.silentError("File too large: $declaredSize bytes (max: ${AppConstants.MAX_BACKUP_SIZE_BYTES})")
        return ImportResult.Error("Backup file is too large (>${AppConstants.MAX_BACKUP_SIZE_BYTES / 1024 / 1024}MB)")
    }

    private companion object {
        const val IMAGE_MEDIA_TYPE = "image/*"
    }
}
