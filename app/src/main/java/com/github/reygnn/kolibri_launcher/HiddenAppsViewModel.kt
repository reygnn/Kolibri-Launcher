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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class HiddenAppsViewModel @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val visibilityRepository: AppVisibilityRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val allAppsMasterList = MutableStateFlow<List<AppInfo>>(emptyList())
    private var initialHiddenComponents: Set<String> = emptySet()
    private val selectedComponents = MutableStateFlow<Set<String>>(emptySet())

    init {
        launchSafe {
            try {
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
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error in combine block")
            }
        }
    }

    /**
     * Kicks off the initial data loading for the ViewModel, fetching the list of all installed
     * apps and the set of currently hidden apps.
     *
     * ARCHITECTURAL NOTE & TESTABILITY:
     * This method is intentionally NOT called from the ViewModel's `init` block.
     *
     * 1.  **The Problem:** Placing data loading in `init` creates a race condition in unit tests.
     *     The coroutine would start immediately upon ViewModel creation, potentially emitting an
     *     event (e.g., a loading error) *before* the test framework (like Turbine) has a chance
     *     to attach a collector to the `eventFlow`. This leads to missed events and flaky tests.
     *
     * 2.  **The Solution:** By making this an `internal` method, the responsibility to start the
     *     data load is shifted to the owner of the ViewModel (the `HiddenAppsActivity`). This
     *     gives tests full control to create the ViewModel, set up their listeners first, and
     *     *then* explicitly call `initialize()` to trigger the action in a predictable order.
     */
    internal fun initialize() {
        launchSafe {
            try {
                val allApps = installedAppsRepository.getInstalledApps().first()
                    .sortedBy { it.displayName.lowercase() }
                allAppsMasterList.value = allApps

                initialHiddenComponents = visibilityRepository.hiddenAppsFlow.first()
                selectedComponents.value = initialHiddenComponents
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error loading hidden apps")
                sendEvent(UiEvent.ShowToast(R.string.error_loading_hidden_apps))
            }
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
        launchSafe {
            try {
                val finalHiddenComponents = selectedComponents.value

                val componentsToHide = finalHiddenComponents - initialHiddenComponents
                val componentsToShow = initialHiddenComponents - finalHiddenComponents

                if (componentsToHide.isNotEmpty() || componentsToShow.isNotEmpty()) {
                    visibilityRepository.updateComponentVisibilities(
                        componentsToHide = componentsToHide,
                        componentsToShow = componentsToShow
                    )
                }

                sendEvent(UiEvent.NavigateUp)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error saving hidden apps")
                sendEvent(UiEvent.NavigateUp)
            }
        }
    }
}