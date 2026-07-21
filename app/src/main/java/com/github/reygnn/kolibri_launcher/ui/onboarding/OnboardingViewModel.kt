/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher.ui.onboarding

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SelectableAppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.CompleteOnboardingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetOnboardingAppsUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingAppsUseCase: GetOnboardingAppsUseCase,
    private val getFavoriteComponentsUseCase: GetFavoriteComponentsUseCase,
    private val completeOnboardingUseCase: CompleteOnboardingUseCase,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<OnboardingEvent>(mainDispatcher) {

    private var launchMode: LaunchMode = LaunchMode.INITIAL_SETUP
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    private val searchQuery = MutableStateFlow("")
    private var isInitialized = false

    // Helper function to send OnboardingEvents safely
    private fun sendOnboardingEvent(event: OnboardingEvent) {
        launchSafe {
            sendEvent(event)
        }
    }

    init {
        launchSafe {
            try {
                combine(
                    onboardingAppsUseCase.onboardingAppsFlow,
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
                        selectableApps = selectableList,
                        selectedApps = selectedAppInfos
                    )
                }.collect { newState ->
                    _uiState.value = newState
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Failed to load apps.")
                sendOnboardingEvent(OnboardingEvent.ShowError("Could not load apps. Please try again."))
            }
        }
    }

    fun setLaunchMode(mode: LaunchMode) {
        this.launchMode = mode

        val titleRes =
            if (mode == LaunchMode.EDIT_FAVORITES) R.string.onboarding_title_edit_favorites else R.string.onboarding_title_welcome
        val subtitleRes =
            if (mode == LaunchMode.EDIT_FAVORITES) R.string.onboarding_subtitle_edit_favorites else R.string.onboarding_subtitle_welcome
        _uiState.update { it.copy(titleResId = titleRes, subtitleResId = subtitleRes) }
    }

    fun loadInitialData() {
        if (isInitialized) return
        isInitialized = true

        launchSafe {
            try {
                val initialSelection = when (launchMode) {
                    LaunchMode.INITIAL_SETUP -> emptySet()
                    LaunchMode.EDIT_FAVORITES -> getFavoriteComponentsUseCase()
                }
                selectedComponents.value = initialSelection
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading initial favorites.")
                sendOnboardingEvent(OnboardingEvent.ShowError("Could not load favorites."))
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onAppToggled(app: AppInfo) {
        launchSafe {
            val currentSelection = selectedComponents.value
            val component = app.componentName

            if (currentSelection.contains(component)) {
                selectedComponents.value = currentSelection - component
            } else {
                if (currentSelection.size >= AppConstants.MAX_FAVORITES_ON_HOME) {
                    sendOnboardingEvent(OnboardingEvent.ShowLimitReachedToast(AppConstants.MAX_FAVORITES_ON_HOME))
                } else {
                    selectedComponents.value = currentSelection + component
                }
            }
        }
    }

    fun onDoneClicked() {
        launchSafe {
            try {
                completeOnboardingUseCase(
                    componentNames = selectedComponents.value.toList(),
                    isInitialSetup = (launchMode == LaunchMode.INITIAL_SETUP)
                )

                sendOnboardingEvent(OnboardingEvent.NavigateToMain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(
                    e,
                    "CRITICAL: Failed to save favorites or complete onboarding."
                )
                sendOnboardingEvent(OnboardingEvent.ShowError("Save failed. Please try again."))
            }
        }
    }
}