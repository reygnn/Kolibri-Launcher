package com.github.reygnn.kolibri_launcher.data

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.json.JSONArray
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
 *
 * Security-Hardened Version mit Fixes für:
 * - OOM Protection (Dateigröße vor Lesen prüfen)
 * - Type Confusion Attacks (validateJsonTypes)
 * - Integer Overflow (korrekte ARGB-Farb-Behandlung)
 * - Float Infinity/NaN (zusätzliche Validierung)
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
                version = AppConstants.BACKUP_VERSION,
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
     * Hybrid-Parsing: kotlinx.serialization für Struktur, org.json für primitive Werte.
     *
     * WICHTIG: validateJsonTypes() wird BEIBEHALTEN als Schutz gegen Type Confusion Attacks.
     */
    private fun parseBackupData(jsonString: String): BackupData? {
        // PHASE 1: Typ-Validierung (Doomsday-Schutz gegen Type Confusion)
        // Dies verhindert Attacken wie: "textColor": "hackerstring"
        if (!validateJsonTypes(jsonString)) {
            Timber.Forest.w("Type validation failed - rejecting malformed backup")
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
     * Merged org.json Werte (snake_case) mit kotlinx.serialization Objekten.
     */
    private fun mergeWithStrictValues(backup: BackupData, jsonString: String): BackupData {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("settings")) return backup
            val settings = root.getJSONObject("settings")

            val enrichedSettings = backup.settings.copy(
                // Strings
                swipeLeftApp = settings.getStrictString("swipe_left_app") ?: backup.settings.swipeLeftApp,
                swipeRightApp = settings.getStrictString("swipe_right_app") ?: backup.settings.swipeRightApp,

                // Ints
                textColor = settings.getStrictInt("text_color") ?: backup.settings.textColor,
                chipBackgroundColor = settings.getStrictInt("chip_bg_color") ?: backup.settings.chipBackgroundColor,
                splitModeThreshold = settings.getStrictInt("split_mode_threshold") ?: backup.settings.splitModeThreshold,

                // Floats
                layoutScale = settings.getStrictFloat("layout_scale") ?: backup.settings.layoutScale,
                verticalPaddingScale = settings.getStrictFloat("vertical_padding_scale") ?: backup.settings.verticalPaddingScale,
                contentTopMarginScale = settings.getStrictFloat("top_margin_scale") ?: backup.settings.contentTopMarginScale,

                // Booleans
                isFontBold = settings.getStrictBool("is_font_bold") ?: backup.settings.isFontBold,
                textShadowEnabled = settings.getStrictBool("text_shadow_enabled") ?: backup.settings.textShadowEnabled,
                showCalendarEvent = settings.getStrictBool("show_calendar_event") ?: backup.settings.showCalendarEvent,
                showAlarm = settings.getStrictBool("show_alarm") ?: backup.settings.showAlarm,
                doubleTapToLockEnabled = settings.getStrictBool("double_tap_to_lock_enabled") ?: backup.settings.doubleTapToLockEnabled,
                swipeDownToNotificationsEnabled = settings.getStrictBool("swipe_down_to_notifications_enabled") ?: backup.settings.swipeDownToNotificationsEnabled,
                autoShowKeyboard = settings.getStrictBool("auto_show_keyboard") ?: backup.settings.autoShowKeyboard,
                autoLaunchApp = settings.getStrictBool("auto_launch_app") ?: backup.settings.autoLaunchApp
            )

            backup.copy(settings = enrichedSettings)
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "Failed to merge with strict values")
            backup
        }
    }

    /**
     * Validiert kritische Felder auf korrekte JSON-Typen.
     * Verhindert Type Confusion Attacks (z.B. "text_color": "hackerstring").
     *
     * WICHTIG: Nutzt snake_case Keys ("text_color"), da diese im JSON stehen.
     */
    private fun validateJsonTypes(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)

            if (!root.has("settings")) {
                return true // Wird später als Fehler behandelt (Missing Field)
            }

            val settings = root.getJSONObject("settings")

            // 1. Integer-Felder (snake_case)
            val intFields = listOf("text_color", "chip_bg_color", "split_mode_threshold")
            for (field in intFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Number) {
                        Timber.Forest.w("Type validation failed: $field is not a number")
                        return false
                    }
                }
            }

            // 2. Float-Felder (snake_case)
            val floatFields = listOf("layout_scale", "vertical_padding_scale", "top_margin_scale")
            for (field in floatFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is Number) {
                        Timber.Forest.w("Type validation failed: $field is not a number")
                        return false
                    }
                    // Infinity/NaN Check
                    val doubleVal = (value as Number).toDouble()
                    if (!doubleVal.isFinite()) {
                        Timber.Forest.w("Type validation failed: $field is Infinity or NaN")
                        return false
                    }
                }
            }

            // 3. Boolean-Felder (snake_case)
            val boolFields = listOf(
                "is_font_bold", "text_shadow_enabled", "show_calendar_event", "show_alarm",
                "double_tap_to_lock_enabled", "swipe_down_to_notifications_enabled",
                "auto_show_keyboard", "auto_launch_app"
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

            // 4. String-Felder (Swipe Apps) (snake_case)
            val stringFields = listOf("swipe_left_app", "swipe_right_app")
            for (field in stringFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    if (settings.get(field) !is String) {
                        Timber.Forest.w("Type validation failed: $field is not a string")
                        return false
                    }
                }
            }

            // 5. Array-Felder (camelCase, da KEIN @SerialName in LauncherSettings)
            val arrayFields = listOf("favoriteComponents", "favoritesOrder", "hiddenComponents")
            for (field in arrayFields) {
                if (settings.has(field) && !settings.isNull(field)) {
                    val value = settings.get(field)
                    if (value !is JSONArray) {
                        Timber.Forest.w("Type validation failed: $field is not an array")
                        return false
                    }
                    // DoS-Schutz
                    if ((value as JSONArray).length() > AppConstants.MAX_ARRAY_ELEMENTS) {
                        Timber.Forest.w("Array size limit exceeded for $field")
                        return false
                    }
                }
            }

            // 6. Map-Feld (camelCase)
            if (settings.has("customAppNames") && !settings.isNull("customAppNames")) {
                val value = settings.get("customAppNames")
                if (value !is JSONObject) {
                    Timber.Forest.w("Type validation failed: customAppNames is not an object")
                    return false
                }
                if ((value as JSONObject).length() > AppConstants.MAX_ARRAY_ELEMENTS) {
                    Timber.Forest.w("Map size limit exceeded for customAppNames")
                    return false
                }
            }

            true
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "JSON validation failed - malformed JSON")
            false
        }
    }

    /**
     * Striktes Parsing mit org.json für Doomsday-Resilience.
     * Mappt snake_case JSON Keys auf camelCase Konstruktor-Parameter.
     */
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

        // Manuelle Extraktion der Listen (camelCase Keys bleiben hier gleich)
        val favoriteComponents = settingsJson.getStrictStringList("favoriteComponents").toSet()
        val favoritesOrder = settingsJson.getStrictStringList("favoritesOrder")
        val hiddenComponents = settingsJson.getStrictStringList("hiddenComponents").toSet()

        // Manuelle Extraktion der Map (camelCase)
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

            // Primitive Werte mapping (snake_case -> property)
            swipeLeftApp = settingsJson.getStrictString("swipe_left_app"),
            swipeRightApp = settingsJson.getStrictString("swipe_right_app"),

            textColor = settingsJson.getStrictInt("text_color"),
            chipBackgroundColor = settingsJson.getStrictInt("chip_bg_color"),
            splitModeThreshold = settingsJson.getStrictInt("split_mode_threshold"),

            layoutScale = settingsJson.getStrictFloat("layout_scale"),
            verticalPaddingScale = settingsJson.getStrictFloat("vertical_padding_scale"),
            contentTopMarginScale = settingsJson.getStrictFloat("top_margin_scale"),

            isFontBold = settingsJson.getStrictBool("is_font_bold"),
            textShadowEnabled = settingsJson.getStrictBool("text_shadow_enabled"),
            showCalendarEvent = settingsJson.getStrictBool("show_calendar_event"),
            showAlarm = settingsJson.getStrictBool("show_alarm"),
            doubleTapToLockEnabled = settingsJson.getStrictBool("double_tap_to_lock_enabled"),
            swipeDownToNotificationsEnabled = settingsJson.getStrictBool("swipe_down_to_notifications_enabled"),
            autoShowKeyboard = settingsJson.getStrictBool("auto_show_keyboard"),
            autoLaunchApp = settingsJson.getStrictBool("auto_launch_app")
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
                settingsManager.setLayoutScale(it.coerceInSafe(AppConstants.LAYOUT_SCALE_MIN, AppConstants.LAYOUT_SCALE_MAX))
            }
            backup.settings.verticalPaddingScale?.let {
                settingsManager.setVerticalPadding(it.coerceInSafe(AppConstants.VERTICAL_PADDING_SCALE_MIN, AppConstants.VERTICAL_PADDING_SCALE_MAX))
            }
            backup.settings.contentTopMarginScale?.let {
                settingsManager.setContentTopMarginScale(it.coerceInSafe(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN, AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX))
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
                settingsManager.setSplitModeThreshold(threshold.coerceInSafe(AppConstants.SPLIT_MODE_THRESHOLD_MIN, AppConstants.SPLIT_MODE_THRESHOLD_MAX))
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
        return try {
            this.getString(key)
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Liest einen Int-Wert aus JSON.
     *
     * WICHTIG: Wir lesen als Long und casten zu Int.
     * Warum? ARGB-Farben wie 0xFFFFFFFF (weiß) = 4294967295 als unsigned.
     * getLong() liest das korrekt, und .toInt() konvertiert es zu -1 (signed).
     */
    private fun JSONObject.getStrictInt(key: String): Int? {
        if (!this.has(key) || this.isNull(key)) return null
        return try {
            // Für Farben korrekt: 4294967295L.toInt() = -1 (0xFFFFFFFF als signed)
            this.getLong(key).toInt()
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Liest einen Float-Wert aus JSON mit Infinity/NaN-Schutz.
     */
    private fun JSONObject.getStrictFloat(key: String): Float? {
        if (!this.has(key) || this.isNull(key)) return null
        return try {
            val doubleVal = this.getDouble(key)
            // FIX: Infinity und NaN ablehnen
            if (!doubleVal.isFinite()) {
                Timber.Forest.w("Rejected non-finite float for $key: $doubleVal")
                null
            } else {
                doubleVal.toFloat()
            }
        } catch (e: JSONException) {
            null
        }
    }

    private fun JSONObject.getStrictBool(key: String): Boolean? {
        if (!this.has(key) || this.isNull(key)) return null
        return try {
            this.getBoolean(key)
        } catch (e: JSONException) {
            null
        }
    }

    private fun JSONObject.getStrictStringList(key: String): List<String> {
        if (!this.has(key) || this.isNull(key)) return emptyList()

        return try {
            val jsonArray = this.getJSONArray(key)
            val list = mutableListOf<String>()
            for (i in 0 until minOf(jsonArray.length(), AppConstants.MAX_ARRAY_ELEMENTS)) {
                // Nur Strings hinzufügen, andere Typen überspringen
                val item = jsonArray.opt(i)
                if (item is String) {
                    list.add(item)
                }
            }
            list
        } catch (e: JSONException) {
            emptyList()
        }
    }

    override suspend fun saveBackupToFile(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided")
                throw BackupException("Invalid file location")
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format: $uriString")
                throw BackupException("Invalid file location format", e)
            }

            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf(AppConstants.SCHEME_CONTENT, AppConstants.SCHEME_FILE)) {
                Timber.Forest.e("Unsupported URI scheme: $scheme")
                throw BackupException("Unsupported file location type")
            }

            // Wir rufen exportToJson auf. Da dies "nur" CPU/Memory ist, ist es hier im IO Block auch okay.
            val jsonString = exportToJson()

            val backupSizeBytes = jsonString.toByteArray().size
            if (backupSizeBytes > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                Timber.Forest.w("Backup size is very large: ${backupSizeBytes / 1024 / 1024} MB")
            }

            // CRITICAL I/O OPERATION
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

    /**
     * FIX 1: OOM Protection - Dateigröße prüfen VOR dem Lesen.
     */
    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) return@withContext ImportResult.Error("Invalid file location")

            val uri = try {
                uriString.toUri()
            } catch (e: Exception) {
                return@withContext ImportResult.Error("Invalid format")
            }

            // FIX 1: OOM Protection - Dateigröße prüfen VOR dem Lesen
            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, AppConstants.MODE_READ_ONLY)?.use { pfd ->
                    pfd.statSize
                } ?: 0L
            } catch (e: Exception) {
                Timber.Forest.w(e, "Could not determine file size, proceeding with caution")
                0L  // Bei Fehler trotzdem versuchen zu lesen (aber mit Limit)
            }

            if (fileSize > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                Timber.Forest.e("File too large: $fileSize bytes (max: $AppConstants.MAX_BACKUP_SIZE_BYTES)")
                return@withContext ImportResult.Error("Backup file is too large (>${AppConstants.MAX_BACKUP_SIZE_BYTES / 1024 / 1024}MB)")
            }

            // CRITICAL I/O OPERATION
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return@withContext ImportResult.Error("Cannot read from selected location")

            // Zusätzlicher Check falls statSize nicht funktioniert hat
            if (jsonString.length > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                return@withContext ImportResult.Error("Backup file is too large")
            }

            // Leere Strings und offensichtlich ungültiges JSON abfangen
            if (jsonString.isBlank()) return@withContext ImportResult.InvalidFormat
            if (!jsonString.trim().startsWith("{")) return@withContext ImportResult.InvalidFormat

            // importFromJson ist suspend, wir rufen es hier auf.
            importFromJson(jsonString, options)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.Forest.e(e, "Error loading backup")
            ImportResult.Error("Failed to load backup: ${e.message}")
        }
    }

    /**
     * FIX 4: Preview mit Dateigrösse-Check VOR dem Lesen.
     */
    override suspend fun previewBackup(uriString: String): BackupPreview? = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided for preview")
                return@withContext null
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format for preview: $uriString")
                return@withContext null
            }

            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf(AppConstants.SCHEME_CONTENT, AppConstants.SCHEME_FILE)) {
                Timber.Forest.e("Unsupported URI scheme for preview: $scheme")
                return@withContext null
            }

            // FIX 4: Dateigröße prüfen VOR dem Lesen (verhindert Memory Spike)
            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize
                } ?: 0L
            } catch (e: Exception) {
                Timber.Forest.w(e, "Could not determine file size for preview")
                0L
            }

            if (fileSize > AppConstants.MAX_PREVIEW_SIZE_BYTES) {
                Timber.Forest.w("File too large for preview: $fileSize bytes (max: $AppConstants.MAX_PREVIEW_SIZE_BYTES)")
                // Für sehr große Dateien: Minimal-Preview mit Warnung
                return@withContext null
            }

            // Jetzt sicher lesen
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: run {
                Timber.Forest.e("Failed to open input stream for preview")
                return@withContext null
            }

            if (!jsonString.trim().startsWith("{")) {
                Timber.Forest.e("File does not appear to be valid JSON")
                return@withContext null
            }

            val backup = try {
                json.decodeFromString<BackupData>(jsonString)
            } catch (e: SerializationException) {
                Timber.Forest.e(e, "Failed to parse backup file for preview")
                return@withContext null
            }

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
                "Preview created: version=${preview.version}, favorites=${preview.favoriteCount}..."
            )
            return@withContext preview

        } catch (e: SecurityException) {
            Timber.Forest.e(e, "Permission denied for preview")
            null
        } catch (e: Exception) {
            Timber.Forest.e(e, "Unexpected error while creating preview")
            null
        }
    }

    private fun isVersionSupported(version: String): Boolean {
        return version == AppConstants.BACKUP_VERSION
    }
}