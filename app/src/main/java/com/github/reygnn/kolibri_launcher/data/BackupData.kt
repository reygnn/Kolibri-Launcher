package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: String = "1.0.0",
    val timestamp: Long = 0L,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val settings: LauncherSettings
)

@Serializable
data class LauncherSettings(
    val favoriteComponents: Set<String> = emptySet(),
    val favoritesOrder: List<String> = emptyList(),
    val hiddenComponents: Set<String> = emptySet(),
    val customAppNames: Map<String, String> = emptyMap(),
    @SerialName("swipe_left_app")
    val swipeLeftApp: String? = null,
    @SerialName("swipe_right_app")
    val swipeRightApp: String? = null,
    @SerialName("text_color")
    val textColor: Int? = null,
    @SerialName("chip_bg_color")
    val chipBackgroundColor: Int? = null,
    @SerialName("text_shadow_enabled")
    val textShadowEnabled: Boolean? = null,
    @SerialName("show_calendar_event")
    val showCalendarEvent: Boolean? = null,
    @SerialName("show_alarm")
    val showAlarm: Boolean? = null,
    @SerialName("double_tap_to_lock_enabled")
    val doubleTapToLockEnabled: Boolean? = null,
    @SerialName("swipe_down_to_notifications_enabled")
    val swipeDownToNotificationsEnabled: Boolean? = null,
    @SerialName("auto_show_keyboard")
    val autoShowKeyboard: Boolean? = null
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
    val importQualityOfLife: Boolean = true
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
                !importQualityOfLife
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
    val hasQualityOfLife: Boolean
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