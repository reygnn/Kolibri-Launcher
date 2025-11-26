package com.github.reygnn.kolibri_launcher.ui.hiddenapps

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SelectableAppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.UpdateHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class HiddenAppsViewModel @Inject constructor(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getHiddenAppsUseCase: GetHiddenAppsUseCase,
    private val updateHiddenAppsUseCase: UpdateHiddenAppsUseCase,
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error updating UI state")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in combine block")
            }
        }
    }

    /**
     * Kicks off the initial data loading for the ViewModel.
     */
    fun initialize() {
        launchSafe {
            try {
                val allApps = getInstalledAppsUseCase().first()
                    .sortedBy { it.displayName.lowercase() }
                allAppsMasterList.value = allApps

                initialHiddenComponents = getHiddenAppsUseCase().first()
                selectedComponents.value = initialHiddenComponents
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
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
                    updateHiddenAppsUseCase(
                        componentsToHide = componentsToHide,
                        componentsToShow = componentsToShow
                    )
                }

                sendEvent(UiEvent.NavigateUp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error saving hidden apps")
                sendEvent(UiEvent.NavigateUp)
            }
        }
    }
}