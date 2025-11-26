/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher.ui.customnames

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class CustomNamesViewModel @Inject constructor(
    private val appNamesManager: CustomNamesRepository,
    private val installedAppsManager: InstalledAppsRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private var masterAppList: List<AppInfo> = emptyList()

    private val _uiState = MutableStateFlow(CustomNamesUiState())
    val uiState: StateFlow<CustomNamesUiState> = _uiState.asStateFlow()

    init {
        launchSafe {
            try {
                _uiState.update { it.copy(isLoading = true) }

                installedAppsManager.getInstalledApps().collect { fullyProcessedList ->
                    try {
                        masterAppList = fullyProcessedList
                        updateUiFromMasterList()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error processing app list")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading apps")
                _uiState.update { it.copy(isLoading = false) }
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
        launchSafe {
            try {
                val app = masterAppList.find { it.packageName == packageName }

                if (customName.isNotBlank() && customName != app?.originalName) {
                    appNamesManager.setCustomNameForPackage(packageName, customName)
                } else {
                    appNamesManager.removeCustomNameForPackage(packageName)
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error setting custom name for $packageName")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        }
    }

    fun removeCustomName(packageName: String) {
        launchSafe {
            try {
                appNamesManager.removeCustomNameForPackage(packageName)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error removing custom name for $packageName")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
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