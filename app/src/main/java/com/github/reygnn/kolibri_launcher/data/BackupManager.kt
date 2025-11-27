package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.BackupException
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup & Restore Manager für Kolibri Launcher Settings.
 *
 * Hybrid Implementation:
 * - Primär: kotlinx.serialization für rückwärtskompatibles Parsing
 * - Fallback: org.json für striktes Parsing bei manuell erstellten/korrupten JSONs
 * - Validierung: Strikte Wertprüfung nach dem Parsing
 */
@Singleton
class BackupManager @Inject constructor(
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    private val appVisibilityManager: HiddenAppsRepository,
    private val appNamesManager: CustomNamesRepository,
    private val installedAppsManager: InstalledAppsRepository,
    private val swipeActionsManager: SwipeActionsRepository,
    private val settingsManager: SettingsRepository,
    @param:ApplicationContext private val context: Context
) : BackupRepository {

    // Wird für Export und Preview genutzt
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun exportToJson(): String {
        return try {
            val favoriteComponents = favoritesManager.favoriteComponentsFlow.first()
            val favoritesOrder = favoritesOrderManager.favoriteComponentsOrderFlow.first()
            val hiddenComponents = appVisibilityManager.hiddenAppsFlow.first()
            val customAppNames = appNamesManager.getAllCustomNames()
            val swipeLeftApp = swipeActionsManager.swipeLeftAppFlow.first()
            val swipeRightApp = swipeActionsManager.swipeRightAppFlow.first()

            val textColor = settingsManager.textColorFlow.first()
            val textShadowEnabled = settingsManager.textShadowEnabledFlow.first()
            val chipBackgroundColor = settingsManager.chipBackgroundColorFlow.first()
            val layoutScale = settingsManager.layoutScaleStateFlow.first()
            val verticalPaddingScale = settingsManager.verticalPaddingStateFlow.first()
            val isFontBold = settingsManager.isFontBoldStateFlow.first()
            val contentTopMarginScale = settingsManager.contentTopMarginScaleFlow.first()

            val showCalendarEvent = settingsManager.showCalendarEventFlow.first()
            val showAlarm = settingsManager.showAlarmFlow.first()
            val doubleTapToLockEnabled = settingsManager.doubleTapToLockEnabledFlow.first()
            val swipeDownToNotificationsEnabled = settingsManager.swipeDownToNotificationsEnabledFlow.first()
            val autoShowKeyboard = settingsManager.autoShowKeyboardFlow.first()
            val autoLaunchApp = settingsManager.autoLaunchAppFlow.first()
            val splitModeThreshold = settingsManager.splitModeThresholdFlow.first()


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
                showCalendarEvent = showCalendarEvent,
                showAlarm = showAlarm,
                doubleTapToLockEnabled = doubleTapToLockEnabled,
                swipeDownToNotificationsEnabled = swipeDownToNotificationsEnabled,
                autoShowKeyboard = autoShowKeyboard,
                autoLaunchApp = autoLaunchApp,
                splitModeThreshold = splitModeThreshold
            )

            val backup = BackupData(
                version = "1.0.0",
                timestamp = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                settings = settings
            )
            json.encodeToString(backup)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error exporting backup")
            throw BackupException("Export failed", e)
        }
    }

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        return try {
            // === PHASE 1: PARSING (Hybrid-Ansatz) ===
            val backup = parseBackupData(jsonString)
                ?: return ImportResult.InvalidFormat

            // === PHASE 2: VALIDIERUNG ===
            if (options.importNothing) {
                return ImportResult.Error("No import options selected")
            }

            if (!isVersionSupported(backup.version)) {
                return ImportResult.UnsupportedVersion(backup.version)
            }

            // === PHASE 3: IMPORT ===
            performImport(backup, options)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error importing backup")
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Hybrid-Parsing mit Fallback und Typ-Validierung.
     *
     * 1. kotlinx.serialization für normale Backups
     * 2. Fallback auf org.json für minimale/manuelle JSONs
     * 3. Typ-Validierung für Doomsday-Schutz
     */
    /**
     * Hybrid-Parsing: kotlinx.serialization für Struktur, org.json für primitive Werte.
     */
    private fun parseBackupData(jsonString: String): BackupData? {
        // PHASE 1: Typ-Validierung (Doomsday-Schutz)
        if (!validateJsonTypes(jsonString)) {
            return null
        }

        // PHASE 2: Parse mit kotlinx.serialization
        val backup = try {
            json.decodeFromString<BackupData>(jsonString)
        } catch (e: SerializationException) {
            TimberWrapper.silentError(e, "kotlinx.serialization failed, trying strict parsing")
            return tryStrictParsing(jsonString)
        } catch (e: IllegalArgumentException) {
            TimberWrapper.silentError(e, "Invalid argument, trying strict parsing")
            return tryStrictParsing(jsonString)
        }

        // PHASE 3: Merge mit org.json Werten (nur überschreiben wenn org.json einen Wert hat)
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

    /**
     * Merged org.json Werte mit kotlinx.serialization Werten.
     * org.json Wert wird nur genommen wenn er nicht null ist, sonst bleibt der Original-Wert.
     */
    private fun mergeWithStrictValues(backup: BackupData, jsonString: String): BackupData {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("settings")) return backup
            val settings = root.getJSONObject("settings")

            val enrichedSettings = backup.settings.copy(
                swipeLeftApp = settings.getStrictString("swipeLeftApp") ?: backup.settings.swipeLeftApp,
                swipeRightApp = settings.getStrictString("swipeRightApp") ?: backup.settings.swipeRightApp,
                textColor = settings.getStrictInt("textColor") ?: backup.settings.textColor,
                chipBackgroundColor = settings.getStrictInt("chipBackgroundColor") ?: backup.settings.chipBackgroundColor,
                splitModeThreshold = settings.getStrictInt("splitModeThreshold") ?: backup.settings.splitModeThreshold,
                layoutScale = settings.getStrictFloat("layoutScale") ?: backup.settings.layoutScale,
                verticalPaddingScale = settings.getStrictFloat("verticalPaddingScale") ?: backup.settings.verticalPaddingScale,
                contentTopMarginScale = settings.getStrictFloat("contentTopMarginScale") ?: backup.settings.contentTopMarginScale,
                isFontBold = settings.getStrictBool("isFontBold") ?: backup.settings.isFontBold,
                textShadowEnabled = settings.getStrictBool("textShadowEnabled") ?: backup.settings.textShadowEnabled,
                showCalendarEvent = settings.getStrictBool("showCalendarEvent") ?: backup.settings.showCalendarEvent,
                showAlarm = settings.getStrictBool("showAlarm") ?: backup.settings.showAlarm,
                doubleTapToLockEnabled = settings.getStrictBool("doubleTapToLockEnabled") ?: backup.settings.doubleTapToLockEnabled,
                swipeDownToNotificationsEnabled = settings.getStrictBool("swipeDownToNotificationsEnabled") ?: backup.settings.swipeDownToNotificationsEnabled,
                autoShowKeyboard = settings.getStrictBool("autoShowKeyboard") ?: backup.settings.autoShowKeyboard,
                autoLaunchApp = settings.getStrictBool("autoLaunchApp") ?: backup.settings.autoLaunchApp
            )

            backup.copy(settings = enrichedSettings)
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "Failed to merge with strict values")
            backup
        }
    }

    /**
     * Validiert kritische Felder auf korrekte JSON-Typen.
     * Verhindert, dass Strings als Integers akzeptiert werden, etc.
     */
    private fun validateJsonTypes(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)

            if (!root.has("settings")) {
                return true // Wird später als Fehler behandelt
            }

            val settings = root.getJSONObject("settings")

            // Validiere Integer-Felder: Wenn vorhanden und nicht null, muss es eine Zahl sein
            val intFields = listOf("textColor", "chipBackgroundColor", "splitModeThreshold")
            for (field in intFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Number) {
                        Timber.Forest.w("Type validation failed: $field is not a number")
                        return false
                    }
                }
            }

            // Validiere Float-Felder
            val floatFields = listOf("layoutScale", "verticalPaddingScale", "contentTopMarginScale")
            for (field in floatFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Number) {
                        Timber.Forest.w("Type validation failed: $field is not a number")
                        return false
                    }
                }
            }

            // Validiere Boolean-Felder
            val boolFields = listOf(
                "isFontBold", "textShadowEnabled", "showCalendarEvent", "showAlarm",
                "doubleTapToLockEnabled", "swipeDownToNotificationsEnabled",
                "autoShowKeyboard", "autoLaunchApp"
            )
            for (field in boolFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Boolean) {
                        Timber.Forest.w("Type validation failed: $field is not a boolean")
                        return false
                    }
                }
            }

            true
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "JSON validation failed")
            false
        }
    }

    /**
     * Striktes Parsing mit org.json für Doomsday-Resilience.
     * Fängt korrupte Datentypen und Integer Overflows sicher ab.
     */
    private fun parseStrictly(jsonString: String): BackupData {
        val root = JSONObject(jsonString)

        val version = if (root.has("version")) root.getString("version") else "1.0.0"
        val timestamp = root.optLong("timestamp", System.currentTimeMillis())

        if (!root.has("settings")) {
            throw JSONException("Missing required field: settings")
        }
        val settingsJson = root.getJSONObject("settings")

        // Manuelle Extraktion der Listen
        val favoriteComponents = settingsJson.getStrictStringList("favoriteComponents").toSet()
        val favoritesOrder = settingsJson.getStrictStringList("favoritesOrder")
        val hiddenComponents = settingsJson.getStrictStringList("hiddenComponents").toSet()

        // Manuelle Extraktion der Map (Custom Names)
        val customAppNames = mutableMapOf<String, String>()
        if (settingsJson.has("customAppNames") && !settingsJson.isNull("customAppNames")) {
            val namesObj = settingsJson.getJSONObject("customAppNames")
            namesObj.keys().forEach { key ->
                customAppNames[key] = namesObj.getString(key)
            }
        }

        val settings = LauncherSettings(
            favoriteComponents = favoriteComponents,
            favoritesOrder = favoritesOrder,
            hiddenComponents = hiddenComponents,
            customAppNames = customAppNames,
            swipeLeftApp = settingsJson.getStrictString("swipeLeftApp"),
            swipeRightApp = settingsJson.getStrictString("swipeRightApp"),
            textColor = settingsJson.getStrictInt("textColor"),
            chipBackgroundColor = settingsJson.getStrictInt("chipBackgroundColor"),
            splitModeThreshold = settingsJson.getStrictInt("splitModeThreshold"),
            layoutScale = settingsJson.getStrictFloat("layoutScale"),
            verticalPaddingScale = settingsJson.getStrictFloat("verticalPaddingScale"),
            contentTopMarginScale = settingsJson.getStrictFloat("contentTopMarginScale"),
            isFontBold = settingsJson.getStrictBool("isFontBold"),
            textShadowEnabled = settingsJson.getStrictBool("textShadowEnabled"),
            showCalendarEvent = settingsJson.getStrictBool("showCalendarEvent"),
            showAlarm = settingsJson.getStrictBool("showAlarm"),
            doubleTapToLockEnabled = settingsJson.getStrictBool("doubleTapToLockEnabled"),
            swipeDownToNotificationsEnabled = settingsJson.getStrictBool("swipeDownToNotificationsEnabled"),
            autoShowKeyboard = settingsJson.getStrictBool("autoShowKeyboard"),
            autoLaunchApp = settingsJson.getStrictBool("autoLaunchApp")
        )

        return BackupData(
            version = version,
            timestamp = timestamp,
            appVersion = root.optString("appVersion", ""),
            settings = settings
        )
    }

    /**
     * Führt den eigentlichen Import durch.
     * Extrahiert aus der alten importFromJson Methode.
     */
    private suspend fun performImport(backup: BackupData, options: ImportOptions): ImportResult {
        // Hole installierte Apps (einmalig)
        val installedApps = installedAppsManager.getInstalledApps().first()
        val installedComponents = installedApps.map { it.componentName }.toSet()

        // PERFORMANCE-OPTIMIERUNG: Convert zu HashSet für O(1) Lookups
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

            favoritesManager.saveFavoriteComponents(validFavorites.toList())
            importedCount += validFavorites.size

            Timber.Forest.i("Imported favorites: $importedCount (skipped: ${backup.settings.favoriteComponents.size - validFavorites.size})")
        }

        // ===== PHASE 2: Import Order =====
        if (options.importOrder) {
            val currentFavorites = favoritesManager.favoriteComponentsFlow.first()
            val currentFavoritesSet = currentFavorites.toHashSet()

            val validOrder = backup.settings.favoritesOrder
                .filter { it in currentFavoritesSet && it in installedComponentsSet }

            favoritesOrderManager.saveOrder(validOrder)
            Timber.Forest.i("Imported order: ${validOrder.size} items")
        }

        // ===== PHASE 3: Import Hidden Apps =====
        if (options.importHiddenApps) {
            val validHidden = backup.settings.hiddenComponents
                .filterTo(HashSet()) { it in installedComponentsSet }

            val skippedHidden = backup.settings.hiddenComponents.size - validHidden.size
            appVisibilityManager.updateComponentVisibilities(
                componentsToHide = validHidden,
                componentsToShow = emptySet()
            )
            Timber.Forest.i("Imported hidden apps: ${validHidden.size} (skipped $skippedHidden)")
        }

        // ===== PHASE 4: Import Custom App Names =====
        if (options.importCustomNames) {
            val validNames = backup.settings.customAppNames
                .filterKeys { it in installedPackagesSet }

            if (validNames.isNotEmpty()) {
                appNamesManager.setCustomNamesInBatch(validNames)
                Timber.Forest.i("Imported custom names: ${validNames.size}")
            }
        }

        // ===== PHASE 5: Import Swipe Actions =====
        if (options.importSwipeActions) {
            var swipeImportedCount = 0
            // Import Left Swipe
            val leftApp = backup.settings.swipeLeftApp
            if (leftApp != null) {
                if (leftApp in installedComponentsSet) {
                    swipeActionsManager.setSwipeAction(SwipeSlot.LEFT, leftApp)
                    swipeImportedCount++
                } else {
                    swipeActionsManager.setSwipeAction(SwipeSlot.LEFT, null)
                    missingApps.add(leftApp)
                }
            }
            // Import Right Swipe
            val rightApp = backup.settings.swipeRightApp
            if (rightApp != null) {
                if (rightApp in installedComponentsSet) {
                    swipeActionsManager.setSwipeAction(SwipeSlot.RIGHT, rightApp)
                    swipeImportedCount++
                } else {
                    swipeActionsManager.setSwipeAction(SwipeSlot.RIGHT, null)
                    missingApps.add(rightApp)
                }
            }
            if (swipeImportedCount > 0) Timber.Forest.i("Imported swipe actions")
        }

        // ===== PHASE 6: Import Gesture Settings =====
        if (options.importGestureSettings) {
            backup.settings.doubleTapToLockEnabled?.let { settingsManager.setDoubleTapToLock(it) }
            backup.settings.swipeDownToNotificationsEnabled?.let { settingsManager.setSwipeDownToNotifications(it) }
        }

        // ===== PHASE 7: Import Theme Settings =====
        if (options.importThemeSettings) {
            backup.settings.textColor?.let { settingsManager.setTextColor(it) }
            backup.settings.chipBackgroundColor?.let { settingsManager.setChipBackgroundColor(it) }
            backup.settings.textShadowEnabled?.let { settingsManager.setTextShadowEnabled(it) }
            backup.settings.isFontBold?.let { settingsManager.setFontBold(it) }

            backup.settings.layoutScale?.let {
                settingsManager.setLayoutScale(it.coerceIn(AppConstants.LAYOUT_SCALE_MIN, AppConstants.LAYOUT_SCALE_MAX))
            }
            backup.settings.verticalPaddingScale?.let {
                settingsManager.setVerticalPadding(it.coerceIn(AppConstants.VERTICAL_PADDING_SCALE_MIN, AppConstants.VERTICAL_PADDING_SCALE_MAX))
            }
            backup.settings.contentTopMarginScale?.let {
                settingsManager.setContentTopMarginScale(it.coerceIn(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN, AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX))
            }
        }

        // ===== PHASE 8: Import Time-Based Events =====
        if (options.importTimeBasedEvents) {
            backup.settings.showCalendarEvent?.let { settingsManager.setShowCalendarEvent(it) }
            backup.settings.showAlarm?.let { settingsManager.setShowAlarm(it) }
        }

        // ===== PHASE 9: Import Quality-of-Life Settings =====
        if (options.importQualityOfLife) {
            backup.settings.autoShowKeyboard?.let { settingsManager.setAutoShowKeyboard(it) }
            backup.settings.autoLaunchApp?.let { settingsManager.setAutoLaunchApp(it) }
        }

        // ===== PHASE 10: Import Power-User Settings =====
        if (options.importPowerUserSettings) {
            backup.settings.splitModeThreshold?.let { threshold ->
                settingsManager.setSplitModeThreshold(threshold.coerceIn(AppConstants.SPLIT_MODE_THRESHOLD_MIN, AppConstants.SPLIT_MODE_THRESHOLD_MAX))
            }
        }

        return ImportResult.Success(
            importedCount = importedCount,
            skippedCount = skippedCount,
            missingApps = missingApps
        )
    }

    // --- Helper für Strict Parsing ---

    private fun JSONObject.getStrictString(key: String): String? {
        if (!this.has(key) || this.isNull(key)) return null
        return this.getString(key)
    }

    private fun JSONObject.getStrictInt(key: String): Int? {
        if (!this.has(key) || this.isNull(key)) return null
        // TRICK: Wir lesen als Long und casten zu Int.
        // Warum? Manche JSON-Generatoren schreiben Farben (0xFFFFFFFF) als große positive Zahl.
        // getInt() wirft bei > 2.1 Mrd eine Exception. getLong() schluckt es, und .toInt() macht daraus korrekt -1.
        return this.getLong(key).toInt()
    }

    private fun JSONObject.getStrictFloat(key: String): Float? {
        if (!this.has(key) || this.isNull(key)) return null
        // getDouble ist robuster für Zahlenformate (1 vs 1.0)
        return this.getDouble(key).toFloat()
    }

    private fun JSONObject.getStrictBool(key: String): Boolean? {
        if (!this.has(key) || this.isNull(key)) return null
        return this.getBoolean(key)
    }

    private fun JSONObject.getStrictStringList(key: String): List<String> {
        if (!this.has(key) || this.isNull(key)) return emptyList()

        val jsonArray = this.getJSONArray(key)
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }

    override suspend fun saveBackupToFile(uriString: String): Boolean {
        return try {
            // 1. Validiere URI-String
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided")
                throw BackupException("Invalid file location")
            }

            // 2. Parse URI mit expliziter Exception-Behandlung
            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format: $uriString")
                throw BackupException("Invalid file location format", e)
            }

            // 3. Prüfe URI-Scheme
            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf("content", "file")) {
                Timber.Forest.e("Unsupported URI scheme: $scheme")
                throw BackupException("Unsupported file location type")
            }

            // 4. Exportiere Backup-Daten
            val jsonString = exportToJson()

            // 5. Prüfe Backup-Größe (optional, aber empfohlen)
            val backupSizeBytes = jsonString.toByteArray().size
            if (backupSizeBytes > 10 * 1024 * 1024) { // 10 MB Limit
                Timber.Forest.w("Backup size is very large: ${backupSizeBytes / 1024 / 1024} MB")
            }

            // 6. Schreibe zu File
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonString.toByteArray())
                Timber.Forest.i("Backup saved successfully to: $uri (${backupSizeBytes / 1024} KB)")
                true
            } ?: run {
                Timber.Forest.e("Failed to open output stream for URI: $uri")
                throw BackupException("Cannot write to selected location")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            Timber.Forest.e(e, "Permission denied for URI")
            throw BackupException("No permission to write to this location", e)
        } catch (e: IOException) {
            Timber.Forest.e(e, "I/O error while saving backup")
            throw BackupException("Failed to write file (storage full or unavailable?)", e)
        } catch (e: Exception) {
            Timber.Forest.e(e, "Unexpected error saving backup")
            throw BackupException("Failed to save backup: ${e.message}", e)
        }
    }

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult {
        return try {
            if (uriString.isBlank()) return ImportResult.Error("Invalid file location")
            val uri = try { uriString.toUri() } catch (e: Exception) { return ImportResult.Error("Invalid format") }

            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return ImportResult.Error("Cannot read from selected location")

            if (jsonString.length > 10 * 1024 * 1024) return ImportResult.Error("Backup file is too large")
            if (!jsonString.trim().startsWith("{")) return ImportResult.InvalidFormat

            importFromJson(jsonString, options)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.Forest.e(e, "Error loading backup")
            ImportResult.Error("Failed to load backup")
        }
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        return try {
            // 1. Validiere URI-String
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided for preview")
                return null
            }

            // 2. Parse URI
            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format for preview: $uriString")
                return null
            }

            // 3. Prüfe URI-Scheme
            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf("content", "file")) {
                Timber.Forest.e("Unsupported URI scheme for preview: $scheme")
                return null
            }

            // 4. Lese File
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                // Limitiere Preview auf erste 1 MB (für Performance)
                val maxPreviewSize = 1024 * 1024
                val buffer = ByteArray(maxPreviewSize)
                val bytesRead = input.read(buffer)

                if (bytesRead < 0) {
                    Timber.Forest.e("Empty file for preview")
                    return null
                }

                String(buffer, 0, bytesRead)
            } ?: run {
                Timber.Forest.e("Failed to open input stream for preview")
                return null
            }

            // 5. Validiere JSON-Format (basic)
            if (!jsonString.trim().startsWith("{")) {
                Timber.Forest.e("File does not appear to be valid JSON")
                return null
            }

            // 6. Parse Backup
            val backup = try {
                json.decodeFromString<BackupData>(jsonString)
            } catch (e: SerializationException) {
                Timber.Forest.e(e, "Failed to parse backup file for preview")
                return null
            }

            // 7. Erstelle Preview
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
                hasTimeBasedEvents = backup.settings.showCalendarEvent != null ||
                        backup.settings.showAlarm != null,
                hasGestureSettings = backup.settings.doubleTapToLockEnabled != null ||
                        backup.settings.swipeDownToNotificationsEnabled != null,
                hasQualityOfLife = backup.settings.autoShowKeyboard != null ||
                        backup.settings.autoLaunchApp != null,
                hasPowerUserSettings = backup.settings.splitModeThreshold != null
            )

            Timber.Forest.i(
                "Preview created: version=${preview.version}, favorites=${preview.favoriteCount}, " +
                        "swipes=L:${preview.hasSwipeLeft}/R:${preview.hasSwipeRight}, " +
                        "theme=${preview.hasThemeSettings}, gestures=${preview.hasGestureSettings}, " +
                        "timeEvents=${preview.hasTimeBasedEvents}, qol=${preview.hasQualityOfLife}, " +
                        "powerUser=${preview.hasPowerUserSettings}"
            )
            preview

        } catch (e: SecurityException) {
            Timber.Forest.e(e, "Permission denied for preview")
            null
        } catch (e: Exception) {
            Timber.Forest.e(e, "Unexpected error while creating preview")
            null
        }
    }

    private fun isVersionSupported(version: String): Boolean {
        return version == "1.0.0"
    }
}