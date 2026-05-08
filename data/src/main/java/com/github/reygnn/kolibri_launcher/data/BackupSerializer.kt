package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerBackup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-logic JSON serialization layer for the backup pipeline.
 *
 * One responsibility: BackupData ↔ JSON-string, plus the type-safe
 * structural derivations that depend only on a parsed [BackupData]
 * (preview, version check, ZIP-file-name → URI resolution).
 *
 * NO dependencies on:
 *  - Repositories (those are the [BackupDataAssembler]'s domain)
 *  - `Context`, `Uri`, `ContentResolver`, file streams (those live in
 *    [BackupRepositoryImpl])
 *  - Coroutine dispatchers (everything here is non-suspending pure
 *    function)
 *
 * That keeps the test surface JVM-only — no Robolectric needed for
 * parser, validator, or strict-fallback edge cases. The five existing
 * BackupRepositoryImpl spec files (Strict, Doomsday, Malformed, Logic,
 * Wallpaper, NamingConvention) are largely tests of this class
 * disguised as tests of the bigger one; over time they should migrate
 * to a `BackupSerializerTest` suite.
 *
 * Hybrid implementation matches the original:
 *  - kotlinx.serialization for forward-compatible reads
 *  - org.json strict pass for type-validated reads of manually-edited
 *    or partially-corrupted backups
 *  - Type validation up front to reject Float-Infinity/NaN, integer
 *    overflow, type-confusion attacks before they reach the parser
 */
@Singleton
class BackupSerializer @Inject constructor() {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ===========================================
    // PUBLIC API
    // ===========================================

    /** Serializes [BackupData] to its JSON string form. */
    fun encodeToJsonString(backup: BackupData): String =
        json.encodeToString(backup)

    /**
     * Parses a JSON backup string. Returns `null` on type-validation
     * failure, parser failure, or unrecoverable malformed input.
     *
     * Parse strategy:
     *  1. Type-validate the raw JSON shape (rejects Infinity/NaN,
     *     wrong-type fields, oversized arrays/maps).
     *  2. Try kotlinx.serialization (forward-compat).
     *  3. On serialization failure, fall back to org.json strict
     *     parsing (recovers from manually-edited/partially-corrupted
     *     backups).
     *  4. Merge org.json's strict values into the kotlinx-parsed
     *     result, so loosely-typed fields the strict path catches
     *     overlay the lenient pass.
     */
    fun parseBackupData(jsonString: String): BackupData? {
        if (!validateJsonTypes(jsonString)) {
            Timber.w("Type validation failed - rejecting malformed backup")
            return null
        }

        val backup = try {
            json.decodeFromString<BackupData>(jsonString)
        } catch (e: SerializationException) {
            // Timber.w (not silentError): an expected, recoverable condition
            // for legacy / hand-edited backups — the strict-parsing fallback
            // is *meant* to handle this. silentError would throw in DEBUG
            // (`crashInDebug`) and tear down the recovery path itself.
            Timber.w(e, "kotlinx.serialization failed, trying strict parsing")
            return tryStrictParsing(jsonString)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Invalid argument, trying strict parsing")
            return tryStrictParsing(jsonString)
        }

        return mergeWithStrictValues(backup, jsonString)
    }

    /**
     * Replaces `imageFileName` references in [backup] with internal
     * URIs from a ZIP-extraction map. Pure data transformation —
     * the actual ZIP I/O happens in [BackupRepositoryImpl] which
     * passes the extracted-image map in.
     */
    fun resolveZipImages(
        backup: BackupData,
        extractedImages: Map<String, String>,
    ): BackupData {
        val settings = backup.settings

        val resolvedLayers = settings.wallpaperLayers.map { layer ->
            val fileName = layer.imageFileName
            val internalUri = if (fileName != null) extractedImages[fileName] else null
            if (internalUri != null) {
                layer.copy(imageUri = internalUri)
            } else {
                layer
            }
        }

        val singleFileName = settings.wallpaperImageFileName
        val resolvedSingleUri = if (singleFileName != null) {
            extractedImages[singleFileName]
        } else {
            null
        }

        return backup.copy(
            settings = settings.copy(
                wallpaperLayers = resolvedLayers,
                wallpaperUri = resolvedSingleUri ?: settings.wallpaperUri,
            ),
        )
    }

    /** Whether the backup's declared format version is one we accept. */
    fun isVersionSupported(version: String): Boolean =
        version == AppConstants.BACKUP_VERSION

    /**
     * Derives a [BackupPreview] from an already-parsed [BackupData].
     * Pure structural extraction — no I/O, no parsing of the original
     * JSON string. Callers that have only the JSON string should call
     * [parseBackupData] first.
     */
    fun buildPreview(backup: BackupData): BackupPreview {
        val hasMultiLayer = backup.settings.wallpaperLayers.isNotEmpty()
        return BackupPreview(
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
            hasPowerUserSettings = backup.settings.secureWindow != null ||
                backup.settings.rotationLocked != null,
        )
    }

    // ===========================================
    // STRICT PARSING (org.json fallback)
    // ===========================================

    private fun tryStrictParsing(jsonString: String): BackupData? {
        return try {
            parseStrictly(jsonString)
        } catch (e: JSONException) {
            // Timber.w (not silentError): both kotlinx and strict paths
            // failed → user-supplied backup is malformed beyond recovery.
            // Returning null is the contract. Programmer-error semantics
            // do not apply — this is external-input failure.
            Timber.w(e, "Strict parsing failed")
            null
        } catch (e: NumberFormatException) {
            Timber.w(e, "Number format error in strict parsing")
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
                rotationLocked = settings.getStrictBool("rotation_locked") ?: backup.settings.rotationLocked,
            )

            backup.copy(settings = enrichedSettings)
        } catch (e: JSONException) {
            // Timber.w (not silentError): graceful degradation — return the
            // kotlinx-parsed backup unchanged when the strict-merge overlay
            // can't be derived. silentError would throw in DEBUG and lose
            // the kotlinx result entirely.
            Timber.w(e, "Failed to merge with strict values")
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
            // Timber.w (not silentError): the wallpaperLayers field is
            // optional and skip-on-malformed is the intended behaviour
            // (other fields still apply). silentError would throw in DEBUG.
            Timber.w(e, "Failed to parse wallpaperLayers array")
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
            label = obj.getStrictString("label"),
        )
    }

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
            rotationLocked = settingsJson.getStrictBool("rotation_locked"),
        )

        return BackupData(
            version = version,
            timestamp = timestamp,
            appVersion = root.optString("appVersion", ""),
            settings = settings,
        )
    }

    // ===========================================
    // TYPE VALIDATION (rejects malformed-but-syntactic JSON)
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
                "wallpaper_scale", "wallpaper_translate_x", "wallpaper_translate_y",
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
                "auto_show_keyboard", "auto_launch_app", "secure_window", "rotation_locked",
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
            // Timber.w (not silentError): user-input rejection — returning
            // false causes parseBackupData to return null, the documented
            // contract for malformed backups. Same semantic as the
            // Timber.w("Type validation failed - …") site above.
            Timber.w(e, "JSON validation failed - malformed JSON")
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
    // STRICT PARSING HELPERS (JSONObject extensions)
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
}
