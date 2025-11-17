package com.github.reygnn.kolibri_launcher.domain.model

data class HomeSettings(
    val sortOrder: SortOrder = SortOrder.ALPHABETICAL,    // Standardwert
    val doubleTapToLockEnabled: Boolean = false,          // Standardwert
    val swipeDownToNotificationsEnabled: Boolean = false, // Standardwert
    val autoLaunchApp: Boolean = false                    // Standardwert
)