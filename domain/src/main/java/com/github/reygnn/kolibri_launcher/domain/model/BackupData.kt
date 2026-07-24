package com.github.reygnn.kolibri_launcher.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class BackupData(
    // Keep in sync with AppConstants.BACKUP_VERSION — a const cannot be
    // referenced across the @Serializable default here. BackupDataAssembler
    // always sets version explicitly, so this default only applies to
    // hand-constructed instances.
    val version: String = "1.0.0",
    val timestamp: Long = 0L,
    val appVersion: String = "",
    val settings: LauncherSettings
)

/**
 * Backup-Repräsentation eines einzelnen Wallpaper-Layers.
 *
 * == ZIP BACKUP (neu) ==
 * imageFileName enthält den relativen Pfad im ZIP-Archiv
 * (z.B. "wallpapers/layer_0.img"). Beim Import wird die Datei
 * extrahiert und in den internen Speicher kopiert.
 *
 * == JSON BACKUP (Legacy) ==
 * imageFileName ist null. imageUri enthält die direkte URI.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WallpaperLayerBackup(
    val id: String? = null,

    @JsonNames("image_uri")
    val imageUri: String? = null,

    /** Relativer Pfad der Bilddatei im ZIP-Archiv. null bei Legacy-JSON-Backups. */
    @JsonNames("image_file_name")
    val imageFileName: String? = null,

    val scale: Float = 1.0f,

    @JsonNames("translate_x")
    val translateX: Float = 0f,

    @JsonNames("translate_y")
    val translateY: Float = 0f,

    val alpha: Float = 1.0f,

    @JsonNames("blend_mode")
    val blendModeName: String? = null,

    @JsonNames("is_visible")
    val isVisible: Boolean = true,

    val label: String? = null
) {
    fun toLayerState(): WallpaperLayerState {
        return WallpaperLayerState(
            // Use newId() (atomic-counter suffix) rather than a bare
            // timestamp so a multi-layer legacy backup with all-null ids
            // restored in the same millisecond can't collide.
            id = id ?: WallpaperLayerState.newId(),
            imageUri = imageUri?.takeIf { it.isNotEmpty() },
            scale = scale,
            translateX = translateX,
            translateY = translateY,
            alpha = alpha,
            blendModeName = blendModeName?.takeIf { it.isNotEmpty() },
            isVisible = isVisible,
            label = label?.takeIf { it.isNotEmpty() }
        )
    }

    companion object {
        fun fromLayerState(state: WallpaperLayerState): WallpaperLayerBackup {
            return WallpaperLayerBackup(
                id = state.id,
                imageUri = state.imageUri,
                scale = state.scale,
                translateX = state.translateX,
                translateY = state.translateY,
                alpha = state.alpha,
                blendModeName = state.blendModeName,
                isVisible = state.isVisible,
                label = state.label
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LauncherSettings(
    @JsonNames("favorite_components")
    val favoriteComponents: Set<String> = emptySet(),
    @JsonNames("favorites_order")
    val favoritesOrder: List<String> = emptyList(),
    @JsonNames("hidden_components")
    val hiddenComponents: Set<String> = emptySet(),
    @JsonNames("custom_app_names")
    val customAppNames: Map<String, String> = emptyMap(),

    @JsonNames("swipe_left_app")
    val swipeLeftApp: String? = null,
    @JsonNames("swipe_right_app")
    val swipeRightApp: String? = null,

    @JsonNames("text_color")
    val textColor: Int? = null,
    @JsonNames("chip_bg_color")
    val chipBackgroundColor: Int? = null,
    @JsonNames("text_shadow_enabled")
    val textShadowEnabled: Boolean? = null,
    @JsonNames("layout_scale")
    val layoutScale: Float? = null,
    @JsonNames("vertical_padding_scale")
    val verticalPaddingScale: Float? = null,
    @JsonNames("is_font_bold")
    val isFontBold: Boolean? = null,
    @JsonNames("top_margin_scale")
    val contentTopMarginScale: Float? = null,

    /**
     * Persisted as the [FavoritesAlignment] enum's `name` string
     * (`"START"` / `"CENTER"` / `"END"`). Optional — legacy backups
     * created before this field existed deserialize as `null` and the
     * import path leaves the user's current value untouched.
     */
    @JsonNames("favorites_alignment")
    val favoritesAlignment: String? = null,

    /**
     * Persisted as the [WallpaperSurfaceMode] enum's `name` string
     * (`"AUTO"` / `"LIGHT"` / `"DARK"`). Optional — legacy backups
     * created before this field existed deserialize as `null` and the
     * import path leaves the user's current value untouched. Same
     * skip-on-unknown semantics as [favoritesAlignment].
     */
    @JsonNames("wallpaper_surface_mode")
    val wallpaperSurfaceMode: String? = null,

    // --- Wallpaper Single-Layer (Backward Compat) ---

    @JsonNames("wallpaper_uri")
    val wallpaperUri: String? = null,
    @JsonNames("wallpaper_scale")
    val wallpaperScale: Float? = null,
    @JsonNames("wallpaper_translate_x")
    val wallpaperTranslateX: Float? = null,
    @JsonNames("wallpaper_translate_y")
    val wallpaperTranslateY: Float? = null,

    /** Relativer Pfad des Single-Layer Bildes im ZIP. null bei Legacy-JSON. */
    @JsonNames("wallpaper_image_file")
    val wallpaperImageFileName: String? = null,

    // --- Wallpaper Multi-Layer ---

    @JsonNames("wallpaper_layers")
    val wallpaperLayers: List<WallpaperLayerBackup> = emptyList(),

    @JsonNames("show_calendar_event")
    val showCalendarEvent: Boolean? = null,
    @JsonNames("show_alarm")
    val showAlarm: Boolean? = null,
    @JsonNames("double_tap_to_lock_enabled")
    val doubleTapToLockEnabled: Boolean? = null,
    @JsonNames("swipe_down_to_notifications_enabled")
    val swipeDownToNotificationsEnabled: Boolean? = null,
    @JsonNames("auto_show_keyboard")
    val autoShowKeyboard: Boolean? = null,
    @JsonNames("auto_launch_app")
    val autoLaunchApp: Boolean? = null,

    /**
     * Persisted as the [SortOrder] enum's `name` string (`"ALPHABETICAL"`
     * / `"TIME_WEIGHTED_USAGE"`). Optional — legacy backups created
     * before this field existed deserialize as `null` and the import
     * path leaves the user's current value untouched. Same
     * skip-on-unknown semantics as [favoritesAlignment].
     */
    @JsonNames("sort_order")
    val sortOrder: String? = null,
    @JsonNames("secure_window")
    val secureWindow: Boolean? = null,
    @JsonNames("rotation_locked")
    val rotationLocked: Boolean? = null
)

data class ImportOptions(
    val importFavorites: Boolean = true,
    val importOrder: Boolean = true,
    val importHiddenApps: Boolean = true,
    val importCustomNames: Boolean = true,
    val importSwipeActions: Boolean = true,
    val importThemeSettings: Boolean = true,
    val importGestureSettings: Boolean = true,
    val importTimeBasedEvents: Boolean = true,
    val importQualityOfLife: Boolean = true,
    val importPowerUserSettings: Boolean = true
) {
    val importNothing: Boolean
        get() = !importFavorites &&
                !importOrder &&
                !importHiddenApps &&
                !importCustomNames &&
                !importSwipeActions &&
                !importThemeSettings &&
                !importGestureSettings &&
                !importTimeBasedEvents &&
                !importQualityOfLife &&
                !importPowerUserSettings
}

data class BackupPreview(
    val version: String,
    val timestamp: Long,
    val favoriteCount: Int,
    val orderCount: Int,
    val hiddenCount: Int,
    val customNamesCount: Int,
    val hasSwipeLeft: Boolean,
    val hasSwipeRight: Boolean,
    val hasThemeSettings: Boolean,
    val hasWallpaper: Boolean,
    val wallpaperLayerCount: Int = 0,
    val hasGestureSettings: Boolean,
    val hasTimeBasedEvents: Boolean,
    val hasQualityOfLife: Boolean,
    val hasPowerUserSettings: Boolean
)

sealed class ImportResult {
    data class Success(
        val importedCount: Int,
        val skippedCount: Int,
        val missingApps: Set<String>
    ) : ImportResult()
    data class UnsupportedVersion(val version: String) : ImportResult()
    data class LimitExceeded(val packageCount: Int, val limit: Int) : ImportResult()
    object InvalidFormat : ImportResult()
    data class Error(val message: String) : ImportResult()
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)