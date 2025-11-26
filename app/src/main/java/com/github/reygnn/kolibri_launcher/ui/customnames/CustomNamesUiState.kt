package com.github.reygnn.kolibri_launcher.ui.customnames

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Definiert den Zustand der AppNamesActivity-UI.
 *
 * @param displayedApps Die gefilterte Liste der Apps, die dem Benutzer angezeigt wird.
 * @param appsWithCustomNames Eine separate Liste nur der Apps, die umbenannt wurden (für die Chips).
 * @param isLoading Zeigt an, ob die anfängliche App-Liste geladen wird.
 * @param searchQuery Der aktuelle vom Benutzer eingegebene Suchtext.
 */
data class CustomNamesUiState(
    val displayedApps: List<AppInfo> = emptyList(),
    val appsWithCustomNames: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = ""
)