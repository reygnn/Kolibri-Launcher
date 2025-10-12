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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class HiddenAppsViewModel @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val visibilityRepository: AppVisibilityRepository
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val allAppsMasterList = MutableStateFlow<List<AppInfo>>(emptyList())
    private var initialHiddenComponents: Set<String> = emptySet()
    private val selectedComponents = MutableStateFlow<Set<String>>(emptySet())

    init {
        launchSafe(
            onError = { e ->
                TimberWrapper.silentError(e, "Error in combine block")
            }
        ) {
            combine(
                allAppsMasterList,
                selectedComponents,
                searchQuery
            ) { allApps, selected, query ->
                val filteredApps = if (query.isBlank()) {
                    allApps
                } else {
                    allApps.filter { it.displayName.contains(query, ignoreCase = true) }
                }

                val selectableList = filteredApps.map { app ->
                    SelectableAppInfo(
                        appInfo = app,
                        isSelected = selected.contains(app.componentName)
                    )
                }

                val selectedAppInfos = allApps
                    .filter { selected.contains(it.componentName) }
                    .sortedBy { it.displayName.lowercase() }

                _uiState.value.copy(
                    titleResId = R.string.hidden_apps_title_screen,
                    subtitleResId = R.string.hidden_apps_subtitle_screen,
                    selectableApps = selectableList,
                    selectedApps = selectedAppInfos
                )
            }.collect { newState ->
                try {
                    _uiState.value = newState
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error updating UI state")
                }
            }
        }
        initialize()
    }

    private fun initialize() {
        launchSafe(
            onError = { e ->
                TimberWrapper.silentError(e, "Error loading hidden apps")
                sendEvent(UiEvent.ShowToast(R.string.error_loading_hidden_apps))
            }
        ) {
            val allApps = installedAppsRepository.getInstalledApps().first()
                .sortedBy { it.displayName.lowercase() }
            allAppsMasterList.value = allApps

            initialHiddenComponents = visibilityRepository.hiddenAppsFlow.first()
            selectedComponents.value = initialHiddenComponents
        }
    }

    fun onSearchQueryChanged(query: String) {
        executeSafe {
            searchQuery.value = query
        }
    }

    fun onAppToggled(app: AppInfo) {
        executeSafe {
            val currentSelection = selectedComponents.value
            val component = app.componentName
            selectedComponents.value = if (currentSelection.contains(component)) {
                currentSelection - component
            } else {
                currentSelection + component
            }
        }
    }

    fun onDoneClicked() {
        launchSafe(
            onError = { e ->
                sendEvent(UiEvent.ShowToast(R.string.error_saving_hidden_apps))
                TimberWrapper.silentError(e, "Error saving hidden apps")
                sendEvent(UiEvent.NavigateUp)
            }
        ) {
            val finalHiddenComponents = selectedComponents.value

            val componentsToHide = finalHiddenComponents - initialHiddenComponents
            val componentsToShow = initialHiddenComponents - finalHiddenComponents

            componentsToHide.forEach { component ->
                try {
                    visibilityRepository.hideComponent(component)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Failed to hide component: $component")
                }
            }

            componentsToShow.forEach { component ->
                try {
                    visibilityRepository.showComponent(component)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Failed to show component: $component")
                }
            }

            sendEvent(UiEvent.NavigateUp)
        }
    }
}