/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Definiert den Zustand der AppNamesActivity-UI.
 *
 * @param displayedApps Die gefilterte Liste der Apps, die dem Benutzer angezeigt wird.
 * @param appsWithCustomNames Eine separate Liste nur der Apps, die umbenannt wurden (für die Chips).
 * @param isLoading Zeigt an, ob die anfängliche App-Liste geladen wird.
 * @param searchQuery Der aktuelle vom Benutzer eingegebene Suchtext.
 */
data class AppNamesUiState(
    val displayedApps: List<AppInfo> = emptyList(),
    val appsWithCustomNames: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = ""
)

@HiltViewModel
class AppNamesViewModel @Inject constructor(
    private val appNamesManager: AppNamesRepository,
    private val installedAppsManager: InstalledAppsRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel(mainDispatcher) {

    private var masterAppList: List<AppInfo> = emptyList()

    private val _uiState = MutableStateFlow(AppNamesUiState())
    val uiState: StateFlow<AppNamesUiState> = _uiState.asStateFlow()

    init {
        // CHANGED: launchSafe statt viewModelScope.launch
        launchSafe(
            onError = { e ->
                TimberWrapper.silentError(e, "Error loading apps")
                _uiState.update { it.copy(isLoading = false) }
            }
        ) {
            _uiState.update { it.copy(isLoading = true) }

            installedAppsManager.getInstalledApps().collect { fullyProcessedList ->
                try {
                    masterAppList = fullyProcessedList
                    updateUiFromMasterList()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error processing app list")
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        executeSafe {
            _uiState.update { it.copy(searchQuery = query) }
            updateUiFromMasterList()
        }
    }

    // CHANGED: launchSafe mit error handling
    fun setCustomName(packageName: String, customName: String) {
        launchSafe(
            onError = { e ->
                TimberWrapper.silentError(e, "Error setting custom name for $packageName")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        ) {
            val app = masterAppList.find { it.packageName == packageName }

            if (customName.isNotBlank() && customName != app?.originalName) {
                appNamesManager.setCustomNameForPackage(packageName, customName)
            } else {
                appNamesManager.removeCustomNameForPackage(packageName)
            }
        }
    }

    fun removeCustomName(packageName: String) {
        launchSafe(
            onError = { e ->
                TimberWrapper.silentError(e, "Error removing custom name for $packageName")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        ) {
            appNamesManager.removeCustomNameForPackage(packageName)
        }
    }

    private fun updateUiFromMasterList() {
        executeSafe(
            onError = { e ->
                TimberWrapper.silentError(e, "Error updating UI from master list")
            }
        ) {
            val query = _uiState.value.searchQuery

            val filteredList = if (query.isBlank()) {
                masterAppList
            } else {
                masterAppList.filter {
                    it.displayName.contains(query, ignoreCase = true) ||
                            it.originalName.contains(query, ignoreCase = true)
                }
            }

            val customNameApps = masterAppList.filter { it.originalName != it.displayName }

            _uiState.update {
                it.copy(
                    displayedApps = filteredList,
                    appsWithCustomNames = customNameApps,
                    isLoading = false
                )
            }
        }
    }
}