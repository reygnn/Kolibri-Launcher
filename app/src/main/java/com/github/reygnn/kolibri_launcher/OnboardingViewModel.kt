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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SelectableAppInfo(
    val appInfo: AppInfo,
    val isSelected: Boolean
)

data class OnboardingUiState(
    val titleResId: Int = R.string.onboarding_title_welcome,
    val subtitleResId: Int = R.string.onboarding_subtitle_welcome,
    val selectableApps: List<SelectableAppInfo> = emptyList(),
    val selectedApps: List<AppInfo> = emptyList()
)

enum class LaunchMode {
    INITIAL_SETUP,
    EDIT_FAVORITES
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingAppsUseCase: GetOnboardingAppsUseCaseRepository,
    private val favoritesRepository: FavoritesRepository,
    private val settingsRepository: SettingsRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<OnboardingEvent>(mainDispatcher),
    OnboardingViewModelInterface {

    private var launchMode: LaunchMode = LaunchMode.INITIAL_SETUP
    private val _uiState = MutableStateFlow(OnboardingUiState())
    override val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    private val searchQuery = MutableStateFlow("")
    private var isInitialized = false

    // Helper function to send OnboardingEvents safely
    private suspend fun sendOnboardingEvent(event: OnboardingEvent) {
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
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Failed to load apps.")
                sendOnboardingEvent(OnboardingEvent.ShowError("Could not load apps. Please try again."))
            }
        }
    }

    override fun setLaunchMode(mode: LaunchMode) {
        this.launchMode = mode

        val titleRes =
            if (mode == LaunchMode.EDIT_FAVORITES) R.string.onboarding_title_edit_favorites else R.string.onboarding_title_welcome
        val subtitleRes =
            if (mode == LaunchMode.EDIT_FAVORITES) R.string.onboarding_subtitle_edit_favorites else R.string.onboarding_subtitle_welcome
        _uiState.update { it.copy(titleResId = titleRes, subtitleResId = subtitleRes) }
    }

    override  fun loadInitialData() {
        if (isInitialized) return
        isInitialized = true

        launchSafe {
            try {
                val initialSelection = when (launchMode) {
                    LaunchMode.INITIAL_SETUP -> emptySet()
                    LaunchMode.EDIT_FAVORITES -> {
                        favoritesRepository.favoriteComponentsFlow.first()
                    }
                }
                selectedComponents.value = initialSelection
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error loading initial favorites.")
                sendOnboardingEvent(OnboardingEvent.ShowError("Could not load favorites."))
            }
        }
    }

    override fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    override fun onAppToggled(app: AppInfo) {
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

    override fun onDoneClicked() {
        launchSafe {
            try {
                favoritesRepository.saveFavoriteComponents(selectedComponents.value.toList())

                if (launchMode == LaunchMode.INITIAL_SETUP) {
                    settingsRepository.setOnboardingCompleted()
                }

                sendOnboardingEvent(OnboardingEvent.NavigateToMain)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "CRITICAL: Failed to save favorites or complete onboarding.")
                sendOnboardingEvent(OnboardingEvent.ShowError("Save failed. Please try again."))
            }
        }
    }
}