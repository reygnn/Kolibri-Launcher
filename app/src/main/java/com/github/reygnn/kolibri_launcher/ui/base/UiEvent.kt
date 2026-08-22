package com.github.reygnn.kolibri_launcher.ui.base

import android.widget.Toast
import androidx.annotation.StringRes
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent

/**
 * Definiert alle einmaligen Events, die ein ViewModel an die UI senden kann.
 */
sealed class UiEvent {
    data class ShowToast(@param:StringRes val messageResId: Int) : UiEvent()

    /**
     * @param duration one of [Toast.LENGTH_SHORT] / [Toast.LENGTH_LONG]. Defaults to
     * `LENGTH_LONG` so existing user-facing callers keep their behaviour; the wallpaper
     * cache debug toasts (F10) opt into `LENGTH_SHORT`.
     */
    data class ShowToastFromString(
        val message: String,
        val duration: Int = Toast.LENGTH_LONG,
    ) : UiEvent()
    object NavigateUp : UiEvent()

    object ShowAppDrawer : UiEvent()
    object ShowSettings : UiEvent()
    object OpenClock : UiEvent()
    object OpenCalendar : UiEvent()
    object OpenBatterySettings : UiEvent()
    data class LaunchApp(val app: AppInfo) : UiEvent()
    data class ShowRecentApps(val apps: List<AppInfo>) : UiEvent()

    /**
     * Show the upcoming time-based events (alarms + calendar) in a dialog.
     * Fired by a home double-tap or a tap on the events indicator; only sent
     * when the list is non-empty (an empty list means no indicator and a
     * silent double-tap).
     */
    data class ShowTimeBasedEventsDialog(val events: List<TimeBasedEvent>) : UiEvent()
    object RefreshAppDrawer : UiEvent()
    data object ShowCustomizationOptions : UiEvent()
    data object ShowColorPickerDialog : UiEvent()

    data object OpenWallpaperPicker : UiEvent()
    data object EnterWallpaperEditMode : UiEvent()
    data object ExitWallpaperEditMode : UiEvent()

    /**
     * The favorites order has been persisted successfully. The
     * `FavoritesSortFragment` uses this to relay a `setFragmentResult`
     * back to its parent so other screens can refresh. Only consumed by
     * that fragment; other screens ignore it.
     */
    data object FavoritesOrderChanged : UiEvent()
}