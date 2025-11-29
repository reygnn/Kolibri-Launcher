package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class BackupData(
    val version: String = "1.0.0",
    val timestamp: Long = 0L,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val settings: LauncherSettings
)

// Legacy backup compatibility: @JsonNames allows deserialization of old snake_case
// keys while new exports use camelCase (the property name) by default.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LauncherSettings(
    // @JsonNames allows users to write backup files in either camelCase or snake_case.
    // Exports always use camelCase (the property name).
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
    @JsonNames("split_mode_threshold")
    val splitModeThreshold: Int? = null

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