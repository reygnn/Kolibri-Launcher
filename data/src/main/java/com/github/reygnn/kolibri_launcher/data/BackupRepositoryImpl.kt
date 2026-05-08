package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.BackupException
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerBackup
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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

    /**
     * Whether [uri] points to a ZIP archive (magic bytes 0x50 0x4B = "PK").
     */
    private fun isZipFile(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val magic = ByteArray(2)
                val read = input.read(magic)
                read == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
            } ?: false
        } catch (e: Throwable) {
            false
        }
    }

    // ===========================================
    // ZIP EXPORT
    // ===========================================

    /**
     * Writes a ZIP backup with embedded wallpaper images.
     *
     * 1. Builds [BackupData] with `imageFileName` references
     * 2. Collects local image files
     * 3. Writes ZIP with `backup.json` + image files
     */
    private fun writeZipBackup(uri: Uri, backupData: BackupData) {
        val imageEntries = mutableListOf<Pair<String, File>>() // (zipEntryName, localFile)
        val dedupSet = mutableSetOf<String>()

        // Multi-layer: each layer gets a filename
        val layersWithFileNames = backupData.settings.wallpaperLayers.mapIndexed { index, layer ->
            val imageUriStr = layer.imageUri
            if (imageUriStr != null) {
                val file = resolveToLocalFile(imageUriStr)
                if (file != null && file.exists()) {
                    val entryName = "wallpapers/layer_$index.img"
                    if (dedupSet.add(file.absolutePath)) {
                        imageEntries.add(entryName to file)
                    }
                    layer.copy(imageFileName = entryName)
                } else {
                    layer
                }
            } else {
                layer
            }
        }

        // Single-layer fallback image
        var singleLayerFileName: String? = null
        val singleUri = backupData.settings.wallpaperUri

        if (singleUri != null && backupData.settings.wallpaperLayers.isEmpty()) {
            val file = resolveToLocalFile(singleUri)
            if (file != null && file.exists()) {
                singleLayerFileName = "wallpapers/single.img"
                if (dedupSet.add(file.absolutePath)) {
                    imageEntries.add(singleLayerFileName to file)
                }
            }
        } else if (imageEntries.isNotEmpty()) {
            // Multi-layer: the single-layer field references layer 0 (same file)
            singleLayerFileName = layersWithFileNames.firstOrNull()?.imageFileName
        }

        val finalBackup = backupData.copy(
            settings = backupData.settings.copy(
                wallpaperLayers = layersWithFileNames,
                wallpaperImageFileName = singleLayerFileName,
            ),
        )

        val jsonString = serializer.encodeToJsonString(finalBackup)

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("backup.json"))
                zipOut.write(jsonString.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                for ((entryName, file) in imageEntries) {
                    zipOut.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        } ?: throw BackupException("Cannot write to selected location")

        Timber.i("ZIP backup saved: ${imageEntries.size} image(s) embedded")
    }

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
     */
    private suspend fun importFromZip(uri: Uri, options: ImportOptions): ImportResult {
        var jsonString: String? = null
        val extractedImages = mutableMapOf<String, String>() // zipEntryName → internal URI string

        // 1. Extract ZIP
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "backup.json" -> {
                                jsonString = zipIn.readBytes().toString(Charsets.UTF_8)
                            }
                            entry.name.startsWith("wallpapers/") && !entry.isDirectory -> {
                                val internalUri = wallpaperFileManager.copyFromInputStream(zipIn)
                                if (internalUri != null) {
                                    extractedImages[entry.name] = internalUri.toString()
                                    Timber.d("Extracted ${entry.name} → $internalUri")
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
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
        return try {
            assembler.performImport(resolvedBackup, options, wallpaperRestorer)
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
            TimberWrapper.silentError(e, "Error reading JSON from ZIP")
            null
        }
    }

    // ===========================================
    // WALLPAPER RESTORE (file-system side; called by Assembler)
    // ===========================================

    private suspend fun restoreWallpaperFromBackup(settings: LauncherSettings) {
        if (settings.wallpaperLayers.isNotEmpty()) {
            importMultiLayerWallpaper(settings.wallpaperLayers)
        } else {
            importSingleLayerWallpaper(settings)
        }
    }

    private suspend fun importMultiLayerWallpaper(layerBackups: List<WallpaperLayerBackup>) {
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
            assembler.saveWallpaperStateForRestore(wallpaperState)
            Timber.i("Imported ${validLayerStates.size}/${layerBackups.size} wallpaper layers")
        } else {
            Timber.w("No valid wallpaper layers found, wallpaper not restored")
        }
    }

    private suspend fun importSingleLayerWallpaper(settings: LauncherSettings) {
        val wallpaperUri = settings.wallpaperUri
        if (wallpaperUri.isNullOrBlank()) return

        try {
            val sourceUri = wallpaperUri.toUri()
            val canAccess = try {
                context.contentResolver.openInputStream(sourceUri)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }

            if (canAccess) {
                val internalUri = wallpaperFileManager.copyToInternal(sourceUri)
                if (internalUri != null) {
                    val wallpaperState = WallpaperState(
                        imageUri = internalUri.toString(),
                        scale = settings.wallpaperScale ?: 1.0f,
                        translateX = settings.wallpaperTranslateX ?: 0.0f,
                        translateY = settings.wallpaperTranslateY ?: 0.0f,
                    )
                    assembler.saveWallpaperStateForRestore(wallpaperState)
                    Timber.i("Imported wallpaper settings (single-layer)")
                } else {
                    Timber.w("Failed to copy wallpaper to internal storage")
                }
            } else {
                Timber.w("Wallpaper URI not accessible, skipping: $wallpaperUri")
            }
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): single-layer
            // bitmap copy via WallpaperFileManager.copyToInternal can OOM on
            // large source bitmap. OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Failed to restore wallpaper")
        }
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
            writeZipBackup(uri, backupData)

            Timber.i("Backup saved successfully as ZIP to: $uri")
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
                return@withContext ImportResult.Error("Invalid format")
            }

            // OOM protection: check file size before reading
            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, AppConstants.MODE_READ_ONLY)?.use { pfd ->
                    pfd.statSize
                } ?: 0L
            } catch (e: Exception) {
                Timber.w(e, "Could not determine file size, proceeding with caution")
                0L
            }

            if (fileSize > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                TimberWrapper.silentError("File too large: $fileSize bytes (max: ${AppConstants.MAX_BACKUP_SIZE_BYTES})")
                return@withContext ImportResult.Error("Backup file is too large (>${AppConstants.MAX_BACKUP_SIZE_BYTES / 1024 / 1024}MB)")
            }

            // Format detection: ZIP or JSON?
            if (isZipFile(uri)) {
                Timber.i("Detected ZIP backup format")
                return@withContext importFromZip(uri, options)
            }

            // Legacy: plain JSON
            Timber.i("Detected legacy JSON backup format")
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return@withContext ImportResult.Error("Cannot read from selected location")

            if (jsonString.length > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                return@withContext ImportResult.Error("Backup file is too large")
            }
            if (jsonString.isBlank()) return@withContext ImportResult.InvalidFormat
            if (!jsonString.trim().startsWith("{")) return@withContext ImportResult.InvalidFormat

            importFromJson(jsonString, options)

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
                Timber.w(e, "Could not determine file size for preview")
                0L
            }

            val sizeLimit = if (isZipFile(uri)) {
                AppConstants.MAX_BACKUP_SIZE_BYTES
            } else {
                AppConstants.MAX_PREVIEW_SIZE_BYTES
            }
            if (fileSize > sizeLimit) {
                Timber.w("File too large for preview: $fileSize bytes (max: $sizeLimit)")
                return@withContext null
            }

            // Format detection: ZIP or JSON?
            val jsonString = if (isZipFile(uri)) {
                readJsonFromZip(uri)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                }
            }

            if (jsonString.isNullOrBlank()) {
                TimberWrapper.silentError("Could not read backup content for preview")
                return@withContext null
            }

            if (!jsonString.trim().startsWith("{")) {
                TimberWrapper.silentError("File does not appear to be valid JSON")
                return@withContext null
            }

            val backup = serializer.parseBackupData(jsonString)
                ?: return@withContext null

            val preview = serializer.buildPreview(backup)

            Timber.i(
                "Preview created: version=${preview.version}, favorites=${preview.favoriteCount}, wallpaperLayers=${preview.wallpaperLayerCount}...",
            )
            return@withContext preview

        } catch (e: SecurityException) {
            TimberWrapper.silentError(e, "Permission denied for preview")
            null
        } catch (e: Throwable) {
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
}
