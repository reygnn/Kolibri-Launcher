package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.AppInfo
import com.github.reygnn.kolibri_launcher.OnboardingUiState
import kotlinx.coroutines.flow.StateFlow

interface OnboardingViewModelInterface {
    val uiState: StateFlow<OnboardingUiState>
    fun setLaunchMode(mode: LaunchMode)
    fun loadInitialData()
    fun onSearchQueryChanged(query: String)
    fun onAppToggled(app: AppInfo)
    fun onDoneClicked()
}