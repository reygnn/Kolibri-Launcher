package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.BuildConfig
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
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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

/**
 * Backup & Restore Manager für Kolibri Launcher Settings.
 *
 * == BACKUP FORMAT ==
 * Export: Immer als ZIP-Archiv mit eingebetteten Wallpaper-Bildern.
 * Import: Erkennt automatisch ZIP (neu) und JSON (alt/legacy).
 *
 * ZIP-Struktur:
 * ├── backup.json          (Settings + Layer-Metadaten)
 * └── wallpapers/
 *     ├── layer_0.img      (Bilddaten Layer 0)
 *     ├── layer_1.img      (Bilddaten Layer 1)
 *     └── ...
 *
 * Hybrid Implementation:
 * - Primär: kotlinx.serialization für rückwärtskompatibles Parsing
 * - Fallback: org.json für striktes Parsing bei manuell erstellten/korrupten JSONs
 * - Validierung: Strikte Wertprüfung nach dem Parsing
 *
 * Security-Hardened Version mit Fixes für:
 * - OOM Protection (Dateigröße vor Lesen prüfen)
 * - Type Confusion Attacks (validateJsonTypes)
 * - Integer Overflow (korrekte ARGB-Farb-Behandlung)
 * - Float Infinity/NaN (zusätzliche Validierung)
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val favoritesOrderRepository: FavoritesOrderRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val customNamesRepository: CustomNamesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val swipeActionsRepository: SwipeActionsRepository,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val wallpaperFileManager: WallpaperFileManager,
    @param:ApplicationContext private val context: Context
) : BackupRepository {

    // Wird für Export und Preview genutzt
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ===========================================
    // EXPORT
    // ===========================================

    override suspend fun exportToJson(): String {
        return try {
            json.encodeToString(buildBackupData())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error exporting backup")
            throw BackupException("Export failed", e)
        }
    }

    /**
     * Baut das komplette BackupData-Objekt aus allen Repositories.
     * Wird sowohl von exportToJson() als auch von saveBackupToFile() verwendet.
     */
    private suspend fun buildBackupData(): BackupData {
        val favoriteComponents = favoritesRepository.favoriteComponentsFlow.first()
        val favoritesOrder = favoritesOrderRepository.favoriteComponentsOrderFlow.first()
        val hiddenComponents = hiddenAppsRepository.hiddenAppsFlow.first()
        val customAppNames = customNamesRepository.getAllCustomNames()
        val swipeLeftApp = swipeActionsRepository.swipeLeftAppFlow.first()
        val swipeRightApp = swipeActionsRepository.swipeRightAppFlow.first()

        val textColor = settingsRepository.textColorFlow.first()
        val textShadowEnabled = settingsRepository.textShadowEnabledFlow.first()
        val chipBackgroundColor = settingsRepository.chipBackgroundColorFlow.first()
        val layoutScale = settingsRepository.layoutScaleStateFlow.first()
        val verticalPaddingScale = settingsRepository.verticalPaddingStateFlow.first()
        val isFontBold = settingsRepository.isFontBoldStateFlow.first()
        val contentTopMarginScale = settingsRepository.contentTopMarginScaleFlow.first()

        val wallpaperState = wallpaperRepository.getWallpaperStateSync()

        val showCalendarEvent = settingsRepository.showCalendarEventFlow.first()
        val showAlarm = settingsRepository.showAlarmFlow.first()
        val doubleTapToLockEnabled = settingsRepository.doubleTapToLockEnabledFlow.first()
        val swipeDownToNotificationsEnabled = settingsRepository.swipeDownToNotificationsEnabledFlow.first()
        val autoShowKeyboard = settingsRepository.autoShowKeyboardFlow.first()
        val autoLaunchApp = settingsRepository.autoLaunchAppFlow.first()
        val splitModeThreshold = settingsRepository.splitModeThresholdFlow.first()
        val secureWindow = settingsRepository.secureWindowFlow.first()
        val rotationLocked = settingsRepository.rotationLockedFlow.first()

        // ===== Wallpaper: Multi-Layer Export =====
        val wallpaperUri: String?
        val wallpaperScale: Float?
        val wallpaperTranslateX: Float?
        val wallpaperTranslateY: Float?
        val wallpaperLayers: List<WallpaperLayerBackup>

        if (wallpaperState.isMultiLayer) {
            wallpaperLayers = wallpaperState.layers.map { WallpaperLayerBackup.fromLayerState(it) }
            val firstLayer = wallpaperState.layers.firstOrNull()
            wallpaperUri = firstLayer?.imageUri?.toString()
            wallpaperScale = if (firstLayer?.imageUri != null) firstLayer.scale else null
            wallpaperTranslateX = if (firstLayer?.imageUri != null) firstLayer.translateX else null
            wallpaperTranslateY = if (firstLayer?.imageUri != null) firstLayer.translateY else null
        } else {
            wallpaperLayers = emptyList()
            wallpaperUri = wallpaperState.imageUri?.toString()
            wallpaperScale = if (wallpaperState.imageUri != null) wallpaperState.scale else null
            wallpaperTranslateX = if (wallpaperState.imageUri != null) wallpaperState.translateX else null
            wallpaperTranslateY = if (wallpaperState.imageUri != null) wallpaperState.translateY else null
        }

        val settings = LauncherSettings(
            favoriteComponents = favoriteComponents,
            favoritesOrder = favoritesOrder,
            hiddenComponents = hiddenComponents,
            customAppNames = customAppNames,
            swipeLeftApp = swipeLeftApp,
            swipeRightApp = swipeRightApp,
            textColor = textColor,
            layoutScale = layoutScale,
            verticalPaddingScale = verticalPaddingScale,
            isFontBold = isFontBold,
            contentTopMarginScale = contentTopMarginScale,
            chipBackgroundColor = chipBackgroundColor,
            textShadowEnabled = textShadowEnabled,
            wallpaperUri = wallpaperUri,
            wallpaperScale = wallpaperScale,
            wallpaperTranslateX = wallpaperTranslateX,
            wallpaperTranslateY = wallpaperTranslateY,
            wallpaperLayers = wallpaperLayers,
            showCalendarEvent = showCalendarEvent,
            showAlarm = showAlarm,
            doubleTapToLockEnabled = doubleTapToLockEnabled,
            swipeDownToNotificationsEnabled = swipeDownToNotificationsEnabled,
            autoShowKeyboard = autoShowKeyboard,
            autoLaunchApp = autoLaunchApp,
            splitModeThreshold = splitModeThreshold,
            secureWindow = secureWindow,
            rotationLocked = rotationLocked
        )

        return BackupData(
            version = AppConstants.BACKUP_VERSION,
            timestamp = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            settings = settings
        )
    }

    // ===========================================
    // IMPORT (JSON)
    // ===========================================

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        return try {
            val backup = parseBackupData(jsonString)
                ?: return ImportResult.InvalidFormat

            if (options.importNothing) {
                return ImportResult.Error("No import options selected")
            }

            if (!isVersionSupported(backup.version)) {
                return ImportResult.UnsupportedVersion(backup.version)
            }

            performImport(backup, options)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error importing backup")
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    // ===========================================
    // ZIP FORMAT DETECTION
    // ===========================================

    /**
     * Prüft ob eine Datei ein ZIP-Archiv ist (Magic Bytes: 0x50 0x4B = "PK").
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
     * Schreibt ein ZIP-Backup mit eingebetteten Wallpaper-Bildern.
     *
     * 1. Baut BackupData mit imageFileName-Referenzen
     * 2. Sammelt lokale Bilddateien
     * 3. Erstellt ZIP mit backup.json + Bilddateien
     */
    private fun writeZipBackup(uri: Uri, backupData: BackupData) {
        // 1. Sammle Bilder und weise Dateinamen zu
        val imageEntries = mutableListOf<Pair<String, File>>() // (zipEntryName, localFile)
        val dedupSet = mutableSetOf<String>() // Verhindert doppelte Einträge

        // Multi-Layer: Jedes Layer bekommt einen Dateinamen
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

        // Single-Layer: Fallback-Bild
        var singleLayerFileName: String? = null
        val singleUri = backupData.settings.wallpaperUri

        if (singleUri != null && backupData.settings.wallpaperLayers.isEmpty()) {
            // Echter Single-Layer Modus
            val file = resolveToLocalFile(singleUri)
            if (file != null && file.exists()) {
                singleLayerFileName = "wallpapers/single.img"
                if (dedupSet.add(file.absolutePath)) {
                    imageEntries.add(singleLayerFileName to file)
                }
            }
        } else if (imageEntries.isNotEmpty()) {
            // Multi-Layer: Single-Layer Feld zeigt auf Layer 0 (gleiche Datei)
            singleLayerFileName = layersWithFileNames.firstOrNull()?.imageFileName
        }

        // 2. Finales BackupData mit imageFileName-Referenzen
        val finalBackup = backupData.copy(
            settings = backupData.settings.copy(
                wallpaperLayers = layersWithFileNames,
                wallpaperImageFileName = singleLayerFileName
            )
        )

        val jsonString = json.encodeToString(finalBackup)

        // 3. ZIP schreiben
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                // backup.json
                zipOut.putNextEntry(ZipEntry("backup.json"))
                zipOut.write(jsonString.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                // Bilddateien
                for ((entryName, file) in imageEntries) {
                    zipOut.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        } ?: throw BackupException("Cannot write to selected location")

        val imageCount = imageEntries.size
        Timber.i("ZIP backup saved: ${imageCount} image(s) embedded")
    }

    /**
     * Löst einen URI-String in eine lokale Datei auf.
     * Funktioniert nur für file:// URIs (interne Wallpaper-Dateien).
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
     * Importiert aus einem ZIP-Backup.
     *
     * 1. Extrahiert backup.json und Wallpaper-Bilder
     * 2. Speichert Bilder in internen Speicher
     * 3. Löst imageFileName-Referenzen zu internen URIs auf
     * 4. Führt normalen Import durch
     */
    private suspend fun importFromZip(uri: Uri, options: ImportOptions): ImportResult {
        var jsonString: String? = null
        val extractedImages = mutableMapOf<String, Uri>() // zipEntryName → internal URI

        // 1. ZIP entpacken
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
                                    extractedImages[entry.name] = internalUri
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
            Timber.e("ZIP backup does not contain backup.json")
            return ImportResult.InvalidFormat
        }

        // 2. JSON parsen (bestehende Logik)
        val backup = parseBackupData(jsonContent)
            ?: return ImportResult.InvalidFormat

        if (options.importNothing) return ImportResult.Error("No import options selected")
        if (!isVersionSupported(backup.version)) return ImportResult.UnsupportedVersion(backup.version)

        // 3. Image-Referenzen auflösen: imageFileName → interne URI
        val resolvedBackup = resolveZipImages(backup, extractedImages)

        // 4. Normalen Import durchführen
        Timber.i("ZIP import: ${extractedImages.size} images extracted, starting import")
        return performImport(resolvedBackup, options)
    }

    /**
     * Ersetzt imageFileName-Referenzen im BackupData durch interne URIs
     * der aus dem ZIP extrahierten Bilder.
     */
    private fun resolveZipImages(
        backup: BackupData,
        extractedImages: Map<String, Uri>
    ): BackupData {
        val settings = backup.settings

        // Multi-Layer: imageFileName → interne URI
        val resolvedLayers = settings.wallpaperLayers.map { layer ->
            val fileName = layer.imageFileName
            val internalUri = if (fileName != null) extractedImages[fileName] else null
            if (internalUri != null) {
                layer.copy(imageUri = internalUri.toString())
            } else {
                layer // Kein imageFileName oder nicht im ZIP → URI unverändert lassen
            }
        }

        // Single-Layer: wallpaperImageFileName → interne URI
        val singleFileName = settings.wallpaperImageFileName
        val resolvedSingleUri = if (singleFileName != null) {
            extractedImages[singleFileName]?.toString()
        } else {
            null
        }

        return backup.copy(
            settings = settings.copy(
                wallpaperLayers = resolvedLayers,
                wallpaperUri = resolvedSingleUri ?: settings.wallpaperUri
            )
        )
    }

    /**
     * Liest nur die backup.json aus einem ZIP-Archiv (für Preview).
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
    // PARSING (Hybrid: kotlinx.serialization + org.json)
    // ===========================================

    private fun parseBackupData(jsonString: String): BackupData? {
        if (!validateJsonTypes(jsonString)) {
            Timber.w("Type validation failed - rejecting malformed backup")
            return null
        }

        val backup = try {
            json.decodeFromString<BackupData>(jsonString)
        } catch (e: SerializationException) {
            TimberWrapper.silentError(e, "kotlinx.serialization failed, trying strict parsing")
            return tryStrictParsing(jsonString)
        } catch (e: IllegalArgumentException) {
            TimberWrapper.silentError(e, "Invalid argument, trying strict parsing")
            return tryStrictParsing(jsonString)
        }

        return mergeWithStrictValues(backup, jsonString)
    }

    private fun tryStrictParsing(jsonString: String): BackupData? {
        return try {
            parseStrictly(jsonString)
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "Strict parsing failed")
            null
        } catch (e: NumberFormatException) {
            TimberWrapper.silentError(e, "Number format error")
            null
        }
    }

    private fun mergeWithStrictValues(backup: BackupData, jsonString: String): BackupData {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("settings")) return backup
            val settings = root.getJSONObject("settings")

            val strictLayers = parseWallpaperLayersFromJson(settings)

            val enrichedSettings = backup.settings.copy(
                swipeLeftApp = settings.getStrictString("swipe_left_app") ?: backup.settings.swipeLeftApp,
                swipeRightApp = settings.getStrictString("swipe_right_app") ?: backup.settings.swipeRightApp,
                textColor = settings.getStrictInt("text_color") ?: backup.settings.textColor,
                chipBackgroundColor = settings.getStrictInt("chip_bg_color") ?: backup.settings.chipBackgroundColor,
                splitModeThreshold = settings.getStrictInt("split_mode_threshold") ?: backup.settings.splitModeThreshold,
                layoutScale = settings.getStrictFloat("layout_scale") ?: backup.settings.layoutScale,
                verticalPaddingScale = settings.getStrictFloat("vertical_padding_scale") ?: backup.settings.verticalPaddingScale,
                contentTopMarginScale = settings.getStrictFloat("top_margin_scale") ?: backup.settings.contentTopMarginScale,
                wallpaperUri = settings.getStrictString("wallpaper_uri") ?: backup.settings.wallpaperUri,
                wallpaperScale = settings.getStrictFloat("wallpaper_scale") ?: backup.settings.wallpaperScale,
                wallpaperTranslateX = settings.getStrictFloat("wallpaper_translate_x") ?: backup.settings.wallpaperTranslateX,
                wallpaperTranslateY = settings.getStrictFloat("wallpaper_translate_y") ?: backup.settings.wallpaperTranslateY,
                wallpaperLayers = if (strictLayers != null) strictLayers else backup.settings.wallpaperLayers,
                isFontBold = settings.getStrictBool("is_font_bold") ?: backup.settings.isFontBold,
                textShadowEnabled = settings.getStrictBool("text_shadow_enabled") ?: backup.settings.textShadowEnabled,
                showCalendarEvent = settings.getStrictBool("show_calendar_event") ?: backup.settings.showCalendarEvent,
                showAlarm = settings.getStrictBool("show_alarm") ?: backup.settings.showAlarm,
                doubleTapToLockEnabled = settings.getStrictBool("double_tap_to_lock_enabled") ?: backup.settings.doubleTapToLockEnabled,
                swipeDownToNotificationsEnabled = settings.getStrictBool("swipe_down_to_notifications_enabled") ?: backup.settings.swipeDownToNotificationsEnabled,
                autoShowKeyboard = settings.getStrictBool("auto_show_keyboard") ?: backup.settings.autoShowKeyboard,
                autoLaunchApp = settings.getStrictBool("auto_launch_app") ?: backup.settings.autoLaunchApp,
                secureWindow = settings.getStrictBool("secure_window") ?: backup.settings.secureWindow,
                rotationLocked = settings.getStrictBool("rotation_locked") ?: backup.settings.rotationLocked
            )

            backup.copy(settings = enrichedSettings)
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "Failed to merge with strict values")
            backup
        }
    }

    private fun parseWallpaperLayersFromJson(settings: JSONObject): List<WallpaperLayerBackup>? {
        val key = when {
            settings.has("wallpaperLayers") -> "wallpaperLayers"
            settings.has("wallpaper_layers") -> "wallpaper_layers"
            else -> return null
        }

        if (settings.isNull(key)) return null

        return try {
            val jsonArray = settings.getJSONArray(key)
            val layers = mutableListOf<WallpaperLayerBackup>()

            for (i in 0 until minOf(jsonArray.length(), AppConstants.MAX_ARRAY_ELEMENTS)) {
                val layerObj = jsonArray.optJSONObject(i) ?: continue
                layers.add(parseWallpaperLayerFromJson(layerObj))
            }

            layers
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "Failed to parse wallpaperLayers array")
            null
        }
    }

    private fun parseWallpaperLayerFromJson(obj: JSONObject): WallpaperLayerBackup {
        return WallpaperLayerBackup(
            id = obj.getStrictString("id"),
            imageUri = obj.getStrictString("imageUri")
                ?: obj.getStrictString("image_uri"),
            imageFileName = obj.getStrictString("imageFileName")
                ?: obj.getStrictString("image_file_name"),
            scale = obj.getStrictFloat("scale") ?: 1.0f,
            translateX = obj.getStrictFloat("translateX")
                ?: obj.getStrictFloat("translate_x") ?: 0f,
            translateY = obj.getStrictFloat("translateY")
                ?: obj.getStrictFloat("translate_y") ?: 0f,
            alpha = obj.getStrictFloat("alpha") ?: 1.0f,
            blendModeName = obj.getStrictString("blendModeName")
                ?: obj.getStrictString("blend_mode"),
            isVisible = obj.getStrictBool("isVisible")
                ?: obj.getStrictBool("is_visible") ?: true,
            label = obj.getStrictString("label")
        )
    }

    // ===========================================
    // TYPE VALIDATION
    // ===========================================

    private fun validateJsonTypes(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)

            if (!root.has("settings")) {
                return true
            }

            val settings = root.getJSONObject("settings")

            val intFields = listOf("text_color", "chip_bg_color", "split_mode_threshold")
            for (field in intFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Number) {
                        Timber.w("Type validation failed: $field is not a number")
                        return false
                    }
                }
            }

            val floatFields = listOf(
                "layout_scale", "vertical_padding_scale", "top_margin_scale",
                "wallpaper_scale", "wallpaper_translate_x", "wallpaper_translate_y"
            )
            for (field in floatFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Number) {
                        Timber.w("Type validation failed: $field is not a number")
                        return false
                    }
                    val doubleVal = (value as Number).toDouble()
                    if (!doubleVal.isFinite()) {
                        Timber.w("Type validation failed: $field is Infinity or NaN")
                        return false
                    }
                }
            }

            val boolFields = listOf(
                "is_font_bold", "text_shadow_enabled", "show_calendar_event", "show_alarm",
                "double_tap_to_lock_enabled", "swipe_down_to_notifications_enabled",
                "auto_show_keyboard", "auto_launch_app", "secure_window", "rotation_locked"
            )
            for (field in boolFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Boolean) {
                        Timber.w("Type validation failed: $field is not a boolean")
                        return false
                    }
                }
            }

            val stringFields = listOf("swipe_left_app", "swipe_right_app", "wallpaper_uri")
            for (field in stringFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    if (settings.get(field) !is String) {
                        Timber.w("Type validation failed: $field is not a string")
                        return false
                    }
                }
            }

            val arrayFields = listOf("favoriteComponents", "favoritesOrder", "hiddenComponents")
            for (field in arrayFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is JSONArray) {
                        Timber.w("Type validation failed: $field is not an array")
                        return false
                    }
                    if ((value as JSONArray).length() > AppConstants.MAX_ARRAY_ELEMENTS) {
                        Timber.w("Array size limit exceeded for $field")
                        return false
                    }
                }
            }

            if (settings.has("customAppNames") && !settings.isNull("customAppNames")) {
                val value = settings.get("customAppNames")
                if (value !is JSONObject) {
                    Timber.w("Type validation failed: customAppNames is not an object")
                    return false
                }
                if ((value as JSONObject).length() > AppConstants.MAX_ARRAY_ELEMENTS) {
                    Timber.w("Map size limit exceeded for customAppNames")
                    return false
                }
            }

            val layersKey = when {
                settings.has("wallpaperLayers") -> "wallpaperLayers"
                settings.has("wallpaper_layers") -> "wallpaper_layers"
                else -> null
            }
            if (layersKey != null && !settings.isNull(layersKey)) {
                val value = settings.get(layersKey)
                if (value !is JSONArray) {
                    Timber.w("Type validation failed: $layersKey is not an array")
                    return false
                }
                if ((value as JSONArray).length() > AppConstants.MAX_ARRAY_ELEMENTS) {
                    Timber.w("Array size limit exceeded for $layersKey")
                    return false
                }
                if (!validateWallpaperLayerTypes(value)) {
                    return false
                }
            }

            true
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "JSON validation failed - malformed JSON")
            false
        }
    }

    private fun validateWallpaperLayerTypes(layersArray: JSONArray): Boolean {
        val layerFloatFields = listOf("scale", "translateX", "translate_x", "translateY", "translate_y", "alpha")
        val layerBoolFields = listOf("isVisible", "is_visible")
        val layerStringFields = listOf("id", "imageUri", "image_uri", "imageFileName", "image_file_name", "blendModeName", "blend_mode", "label")

        for (i in 0 until layersArray.length()) {
            val layer = layersArray.optJSONObject(i) ?: continue

            for (field in layerFloatFields) {
                if (layer.has(field) && !layer.isNull(field)) {
                    val value = layer.get(field)
                    if (value !is Number) {
                        Timber.w("Type validation failed: wallpaperLayers[$i].$field is not a number")
                        return false
                    }
                    val doubleVal = (value as Number).toDouble()
                    if (!doubleVal.isFinite()) {
                        Timber.w("Type validation failed: wallpaperLayers[$i].$field is Infinity or NaN")
                        return false
                    }
                }
            }
            for (field in layerBoolFields) {
                if (layer.has(field) && !layer.isNull(field)) {
                    if (layer.get(field) !is Boolean) {
                        Timber.w("Type validation failed: wallpaperLayers[$i].$field is not a boolean")
                        return false
                    }
                }
            }
            for (field in layerStringFields) {
                if (layer.has(field) && !layer.isNull(field)) {
                    if (layer.get(field) !is String) {
                        Timber.w("Type validation failed: wallpaperLayers[$i].$field is not a string")
                        return false
                    }
                }
            }
        }
        return true
    }

    // ===========================================
    // STRICT PARSING (org.json Fallback)
    // ===========================================

    private fun parseStrictly(jsonString: String): BackupData {
        val root = JSONObject(jsonString)

        val version = if (root.has("version") && !root.isNull("version")) {
            root.getString("version")
        } else {
            "1.0.0"
        }
        val timestamp = root.optLong("timestamp", System.currentTimeMillis())

        if (!root.has("settings")) {
            throw JSONException("Missing required field: settings")
        }
        val settingsJson = root.getJSONObject("settings")

        val favoriteComponents = settingsJson.getStrictStringList("favoriteComponents").toSet()
        val favoritesOrder = settingsJson.getStrictStringList("favoritesOrder")
        val hiddenComponents = settingsJson.getStrictStringList("hiddenComponents").toSet()

        val customAppNames = mutableMapOf<String, String>()
        if (settingsJson.has("customAppNames") && !settingsJson.isNull("customAppNames")) {
            val namesObj = settingsJson.getJSONObject("customAppNames")
            namesObj.keys().forEach { key ->
                customAppNames[key] = namesObj.getString(key)
            }
        }

        val wallpaperLayers = parseWallpaperLayersFromJson(settingsJson) ?: emptyList()

        val settings = LauncherSettings(
            favoriteComponents = favoriteComponents,
            favoritesOrder = favoritesOrder,
            hiddenComponents = hiddenComponents,
            customAppNames = customAppNames,
            swipeLeftApp = settingsJson.getStrictString("swipe_left_app"),
            swipeRightApp = settingsJson.getStrictString("swipe_right_app"),
            textColor = settingsJson.getStrictInt("text_color"),
            chipBackgroundColor = settingsJson.getStrictInt("chip_bg_color"),
            splitModeThreshold = settingsJson.getStrictInt("split_mode_threshold"),
            layoutScale = settingsJson.getStrictFloat("layout_scale"),
            verticalPaddingScale = settingsJson.getStrictFloat("vertical_padding_scale"),
            contentTopMarginScale = settingsJson.getStrictFloat("top_margin_scale"),
            wallpaperUri = settingsJson.getStrictString("wallpaper_uri"),
            wallpaperScale = settingsJson.getStrictFloat("wallpaper_scale"),
            wallpaperTranslateX = settingsJson.getStrictFloat("wallpaper_translate_x"),
            wallpaperTranslateY = settingsJson.getStrictFloat("wallpaper_translate_y"),
            wallpaperLayers = wallpaperLayers,
            isFontBold = settingsJson.getStrictBool("is_font_bold"),
            textShadowEnabled = settingsJson.getStrictBool("text_shadow_enabled"),
            showCalendarEvent = settingsJson.getStrictBool("show_calendar_event"),
            showAlarm = settingsJson.getStrictBool("show_alarm"),
            doubleTapToLockEnabled = settingsJson.getStrictBool("double_tap_to_lock_enabled"),
            swipeDownToNotificationsEnabled = settingsJson.getStrictBool("swipe_down_to_notifications_enabled"),
            autoShowKeyboard = settingsJson.getStrictBool("auto_show_keyboard"),
            autoLaunchApp = settingsJson.getStrictBool("auto_launch_app"),
            secureWindow = settingsJson.getStrictBool("secure_window"),
            rotationLocked = settingsJson.getStrictBool("rotation_locked")
        )

        return BackupData(
            version = version,
            timestamp = timestamp,
            appVersion = root.optString("appVersion", ""),
            settings = settings
        )
    }

    // ===========================================
    // PERFORM IMPORT
    // ===========================================

    private suspend fun performImport(backup: BackupData, options: ImportOptions): ImportResult {
        val installedApps = installedAppsRepository.getInstalledApps().first()
        val installedComponents = installedApps.map { it.componentName }.toSet()

        val installedComponentsSet = installedComponents.toHashSet()
        val installedPackagesSet = installedComponents
            .mapTo(HashSet()) { it.split('/')[0] }

        var importedCount = 0
        var skippedCount = 0
        val missingApps = mutableSetOf<String>()

        // ===== PHASE 1: Import Favorites =====
        if (options.importFavorites) {
            val validFavorites = backup.settings.favoriteComponents
                .filterTo(HashSet()) { it in installedComponentsSet }

            skippedCount += backup.settings.favoriteComponents.size - validFavorites.size
            missingApps.addAll(backup.settings.favoriteComponents - installedComponentsSet)

            val uniquePackages = validFavorites
                .mapTo(HashSet()) { it.split('/')[0] }

            if (uniquePackages.size > AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME) {
                return ImportResult.LimitExceeded(
                    packageCount = uniquePackages.size,
                    limit = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
                )
            }

            favoritesRepository.saveFavoriteComponents(validFavorites.toList())
            importedCount += validFavorites.size
            Timber.i("Imported favorites: $importedCount (skipped: ${backup.settings.favoriteComponents.size - validFavorites.size})")
        }

        // ===== PHASE 2: Import Order =====
        if (options.importOrder) {
            val currentFavorites = favoritesRepository.favoriteComponentsFlow.first()
            val currentFavoritesSet = currentFavorites.toHashSet()

            val validOrder = backup.settings.favoritesOrder
                .filter { it in currentFavoritesSet && it in installedComponentsSet }

            favoritesOrderRepository.saveOrder(validOrder)
            Timber.i("Imported order: ${validOrder.size} items")
        }

        // ===== PHASE 3: Import Hidden Apps =====
        if (options.importHiddenApps) {
            val validHidden = backup.settings.hiddenComponents
                .filterTo(HashSet()) { it in installedComponentsSet }

            val skippedHidden = backup.settings.hiddenComponents.size - validHidden.size
            hiddenAppsRepository.updateComponentVisibilities(
                componentsToHide = validHidden,
                componentsToShow = emptySet()
            )
            Timber.i("Imported hidden apps: ${validHidden.size} (skipped $skippedHidden)")
        }

        // ===== PHASE 4: Import Custom App Names =====
        if (options.importCustomNames) {
            val validNames = backup.settings.customAppNames
                .filterKeys { it in installedPackagesSet }

            if (validNames.isNotEmpty()) {
                customNamesRepository.setCustomNamesInBatch(validNames)
                Timber.i("Imported custom names: ${validNames.size}")
            }
        }

        // ===== PHASE 5: Import Swipe Actions =====
        if (options.importSwipeActions) {
            var swipeImportedCount = 0
            val leftApp = backup.settings.swipeLeftApp
            if (leftApp != null) {
                if (leftApp in installedComponentsSet) {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, leftApp)
                    swipeImportedCount++
                } else {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)
                    missingApps.add(leftApp)
                }
            }
            val rightApp = backup.settings.swipeRightApp
            if (rightApp != null) {
                if (rightApp in installedComponentsSet) {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, rightApp)
                    swipeImportedCount++
                } else {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, null)
                    missingApps.add(rightApp)
                }
            }
            if (swipeImportedCount > 0) Timber.i("Imported swipe actions")
        }

        // ===== PHASE 6: Import Gesture Settings =====
        if (options.importGestureSettings) {
            backup.settings.doubleTapToLockEnabled?.let { settingsRepository.setDoubleTapToLock(it) }
            backup.settings.swipeDownToNotificationsEnabled?.let { settingsRepository.setSwipeDownToNotifications(it) }
        }

        // ===== PHASE 7: Import Theme Settings (inkl. Wallpaper) =====
        if (options.importThemeSettings) {
            backup.settings.textColor?.let { settingsRepository.setTextColor(it) }
            backup.settings.chipBackgroundColor?.let { settingsRepository.setChipBackgroundColor(it) }
            backup.settings.textShadowEnabled?.let { settingsRepository.setTextShadowEnabled(it) }
            backup.settings.isFontBold?.let { settingsRepository.setFontBold(it) }

            backup.settings.layoutScale?.let {
                settingsRepository.setLayoutScale(it.coerceInSafe(AppConstants.LAYOUT_SCALE_MIN, AppConstants.LAYOUT_SCALE_MAX))
            }
            backup.settings.verticalPaddingScale?.let {
                settingsRepository.setVerticalPadding(it.coerceInSafe(AppConstants.VERTICAL_PADDING_SCALE_MIN, AppConstants.VERTICAL_PADDING_SCALE_MAX))
            }
            backup.settings.contentTopMarginScale?.let {
                settingsRepository.setContentTopMarginScale(it.coerceInSafe(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN, AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX))
            }

            importWallpaper(backup.settings)
        }

        // ===== PHASE 8: Import Time-Based Events =====
        if (options.importTimeBasedEvents) {
            backup.settings.showCalendarEvent?.let { settingsRepository.setShowCalendarEvent(it) }
            backup.settings.showAlarm?.let { settingsRepository.setShowAlarm(it) }
        }

        // ===== PHASE 9: Import Quality-of-Life Settings =====
        if (options.importQualityOfLife) {
            backup.settings.autoShowKeyboard?.let { settingsRepository.setAutoShowKeyboard(it) }
            backup.settings.autoLaunchApp?.let { settingsRepository.setAutoLaunchApp(it) }
        }

        // ===== PHASE 10: Import Power-User Settings =====
        if (options.importPowerUserSettings) {
            backup.settings.splitModeThreshold?.let { threshold ->
                settingsRepository.setSplitModeThreshold(threshold.coerceInSafe(AppConstants.SPLIT_MODE_THRESHOLD_MIN, AppConstants.SPLIT_MODE_THRESHOLD_MAX))
            }
            backup.settings.secureWindow?.let { settingsRepository.setSecureWindow(it) }
            backup.settings.rotationLocked?.let { settingsRepository.setRotationLocked(it) }
        }

        return ImportResult.Success(
            importedCount = importedCount,
            skippedCount = skippedCount,
            missingApps = missingApps
        )
    }

    // ===========================================
    // WALLPAPER IMPORT
    // ===========================================

    private suspend fun importWallpaper(settings: LauncherSettings) {
        // Nur den DataStore-State zurücksetzen, NICHT die Dateien.
        // Dateien werden in importMultiLayer/importSingleLayer durch
        // copyToInternal() überschrieben. Alte Waisen werden danach aufgeräumt
        // indem nur die tatsächlich referenzierten Dateien behalten werden.
        wallpaperRepository.clearWallpaper()

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
                        validLayerStates.add(layerBackup.toLayerState().copy(imageUri = internalUri))
                    } else {
                        Timber.w("Failed to copy layer $index to internal storage, skipping")
                    }
                } else {
                    Timber.w("Wallpaper layer $index URI not accessible, skipping: $uriString")
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Failed to validate wallpaper layer $index URI")
            }
        }

        if (validLayerStates.isNotEmpty()) {
            val wallpaperState = WallpaperState.multiLayer(validLayerStates)
            wallpaperRepository.saveWallpaperState(wallpaperState)
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
                        imageUri = internalUri,
                        scale = settings.wallpaperScale ?: 1.0f,
                        translateX = settings.wallpaperTranslateX ?: 0.0f,
                        translateY = settings.wallpaperTranslateY ?: 0.0f
                    )
                    wallpaperRepository.saveWallpaperState(wallpaperState)
                    Timber.i("Imported wallpaper settings (single-layer)")
                } else {
                    Timber.w("Failed to copy wallpaper to internal storage")
                }
            } else {
                Timber.w("Wallpaper URI not accessible, skipping: $wallpaperUri")
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to restore wallpaper")
        }
    }

    // ===========================================
    // STRICT PARSING HELPERS
    // ===========================================

    private fun JSONObject.getStrictString(key: String): String? {
        if (!this.has(key) || this.isNull(key)) return null
        return try { this.getString(key) } catch (e: JSONException) { null }
    }

    private fun JSONObject.getStrictInt(key: String): Int? {
        if (!this.has(key) || this.isNull(key)) return null
        return try { this.getLong(key).toInt() } catch (e: JSONException) { null }
    }

    private fun JSONObject.getStrictFloat(key: String): Float? {
        if (!this.has(key) || this.isNull(key)) return null
        return try {
            val doubleVal = this.getDouble(key)
            if (!doubleVal.isFinite()) {
                Timber.w("Rejected non-finite float for $key: $doubleVal")
                null
            } else {
                doubleVal.toFloat()
            }
        } catch (e: JSONException) { null }
    }

    private fun JSONObject.getStrictBool(key: String): Boolean? {
        if (!this.has(key) || this.isNull(key)) return null
        return try { this.getBoolean(key) } catch (e: JSONException) { null }
    }

    private fun JSONObject.getStrictStringList(key: String): List<String> {
        if (!this.has(key) || this.isNull(key)) return emptyList()
        return try {
            val jsonArray = this.getJSONArray(key)
            val list = mutableListOf<String>()
            for (i in 0 until minOf(jsonArray.length(), AppConstants.MAX_ARRAY_ELEMENTS)) {
                val item = jsonArray.opt(i)
                if (item is String) {
                    list.add(item)
                }
            }
            list
        } catch (e: JSONException) { emptyList() }
    }

    // ===========================================
    // FILE I/O: SAVE
    // ===========================================

    override suspend fun saveBackupToFile(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                Timber.e("Empty URI string provided")
                throw BackupException("Invalid file location")
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Invalid URI format: $uriString")
                throw BackupException("Invalid file location format", e)
            }

            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf(AppConstants.SCHEME_CONTENT, AppConstants.SCHEME_FILE)) {
                Timber.e("Unsupported URI scheme: $scheme")
                throw BackupException("Unsupported file location type")
            }

            // BackupData bauen und als ZIP mit Bildern schreiben
            val backupData = buildBackupData()
            writeZipBackup(uri, backupData)

            Timber.i("Backup saved successfully as ZIP to: $uri")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied for URI")
            throw BackupException("No permission to write to this location", e)
        } catch (e: IOException) {
            Timber.e(e, "I/O error while saving backup")
            throw BackupException("Failed to write file (storage full or unavailable?)", e)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error saving backup")
            throw BackupException("Failed to save backup: ${e.message}", e)
        }
    }

    // ===========================================
    // FILE I/O: LOAD
    // ===========================================

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) return@withContext ImportResult.Error("Invalid file location")

            val uri = try {
                uriString.toUri()
            } catch (e: Exception) {
                return@withContext ImportResult.Error("Invalid format")
            }

            // OOM Protection: Dateigröße prüfen VOR dem Lesen
            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, AppConstants.MODE_READ_ONLY)?.use { pfd ->
                    pfd.statSize
                } ?: 0L
            } catch (e: Exception) {
                Timber.w(e, "Could not determine file size, proceeding with caution")
                0L
            }

            if (fileSize > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                Timber.e("File too large: $fileSize bytes (max: ${AppConstants.MAX_BACKUP_SIZE_BYTES})")
                return@withContext ImportResult.Error("Backup file is too large (>${AppConstants.MAX_BACKUP_SIZE_BYTES / 1024 / 1024}MB)")
            }

            // Format-Erkennung: ZIP oder JSON?
            if (isZipFile(uri)) {
                Timber.i("Detected ZIP backup format")
                return@withContext importFromZip(uri, options)
            }

            // Legacy: Plain JSON
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
        } catch (e: Exception) {
            Timber.e(e, "Error loading backup")
            ImportResult.Error("Failed to load backup: ${e.message}")
        }
    }

    // ===========================================
    // FILE I/O: PREVIEW
    // ===========================================

    override suspend fun previewBackup(uriString: String): BackupPreview? = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                Timber.e("Empty URI string provided for preview")
                return@withContext null
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Invalid URI format for preview: $uriString")
                return@withContext null
            }

            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf(AppConstants.SCHEME_CONTENT, AppConstants.SCHEME_FILE)) {
                Timber.e("Unsupported URI scheme for preview: $scheme")
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

            // Format-Erkennung: ZIP oder JSON?
            val jsonString = if (isZipFile(uri)) {
                readJsonFromZip(uri)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                }
            }

            if (jsonString.isNullOrBlank()) {
                Timber.e("Could not read backup content for preview")
                return@withContext null
            }

            if (!jsonString.trim().startsWith("{")) {
                Timber.e("File does not appear to be valid JSON")
                return@withContext null
            }

            val backup = try {
                json.decodeFromString<BackupData>(jsonString)
            } catch (e: SerializationException) {
                Timber.e(e, "Failed to parse backup file for preview")
                return@withContext null
            }

            val hasMultiLayer = backup.settings.wallpaperLayers.isNotEmpty()

            val preview = BackupPreview(
                version = backup.version,
                timestamp = backup.timestamp,
                favoriteCount = backup.settings.favoriteComponents.size,
                orderCount = backup.settings.favoritesOrder.size,
                hiddenCount = backup.settings.hiddenComponents.size,
                customNamesCount = backup.settings.customAppNames.size,
                hasSwipeLeft = backup.settings.swipeLeftApp != null,
                hasSwipeRight = backup.settings.swipeRightApp != null,
                hasThemeSettings = backup.settings.textColor != null ||
                        backup.settings.chipBackgroundColor != null ||
                        backup.settings.textShadowEnabled != null ||
                        backup.settings.layoutScale != null ||
                        backup.settings.verticalPaddingScale != null ||
                        backup.settings.isFontBold != null ||
                        backup.settings.contentTopMarginScale != null,
                hasWallpaper = if (hasMultiLayer) {
                    backup.settings.wallpaperLayers.any { it.imageUri != null || it.imageFileName != null }
                } else {
                    backup.settings.wallpaperUri != null || backup.settings.wallpaperImageFileName != null
                },
                wallpaperLayerCount = if (hasMultiLayer) backup.settings.wallpaperLayers.size else 0,
                hasTimeBasedEvents = backup.settings.showCalendarEvent != null ||
                        backup.settings.showAlarm != null,
                hasGestureSettings = backup.settings.doubleTapToLockEnabled != null ||
                        backup.settings.swipeDownToNotificationsEnabled != null,
                hasQualityOfLife = backup.settings.autoShowKeyboard != null ||
                        backup.settings.autoLaunchApp != null,
                hasPowerUserSettings = backup.settings.splitModeThreshold != null ||
                        backup.settings.secureWindow != null ||
                        backup.settings.rotationLocked != null
            )

            Timber.i(
                "Preview created: version=${preview.version}, favorites=${preview.favoriteCount}, wallpaperLayers=${preview.wallpaperLayerCount}..."
            )
            return@withContext preview

        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied for preview")
            null
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error while creating preview")
            null
        }
    }

    private fun isVersionSupported(version: String): Boolean {
        return version == AppConstants.BACKUP_VERSION
    }
}