package com.github.reygnn.kolibri_launcher

import androidx.annotation.StringRes

/**
 * Definiert alle einmaligen Events, die ein ViewModel an die UI senden kann.
 */
sealed class UiEvent {
    data class ShowToast(@param:StringRes val messageResId: Int) : UiEvent()
    data class ShowToastFromString(val message: String) : UiEvent()
    object NavigateUp : UiEvent()

    object ShowAppDrawer : UiEvent()
    object ShowSettings : UiEvent()
    object OpenClock : UiEvent()
    object OpenCalendar : UiEvent()
    object OpenBatterySettings : UiEvent()
    object ShowAccessibilityDialog : UiEvent()
    data class LaunchApp(val app: AppInfo) : UiEvent()
    object RefreshAppDrawer : UiEvent()
    data object ShowCustomizationOptions : UiEvent()
    data object ShowColorPickerDialog : UiEvent()
}