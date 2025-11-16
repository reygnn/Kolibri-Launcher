package com.github.reygnn.kolibri_launcher.ui.onboarding

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SelectableAppInfo

data class OnboardingUiState(
    val titleResId: Int = R.string.onboarding_title_welcome,
    val subtitleResId: Int = R.string.onboarding_subtitle_welcome,
    val selectableApps: List<SelectableAppInfo> = emptyList(),
    val selectedApps: List<AppInfo> = emptyList()
)