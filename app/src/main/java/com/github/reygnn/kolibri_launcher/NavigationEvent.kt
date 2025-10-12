package com.github.reygnn.kolibri_launcher

/**
 * Repräsentiert alle möglichen Navigations- oder einmaligen UI-Aktionen,
 * die vom HomeViewModel ausgelöst und von der UI-Schicht (Fragment/Activity)
 * ausgeführt werden sollen.
 */
sealed class NavigationEvent {
    data class LaunchApp(val app: AppInfo) : NavigationEvent()
    object ShowAppDrawer : NavigationEvent()
    object ShowSettings : NavigationEvent()
    object OpenClock : NavigationEvent()
    object OpenCalendar : NavigationEvent()
    object OpenBatterySettings : NavigationEvent()
    object LockScreen : NavigationEvent()
    object ShowAccessibilityDialog : NavigationEvent()
}