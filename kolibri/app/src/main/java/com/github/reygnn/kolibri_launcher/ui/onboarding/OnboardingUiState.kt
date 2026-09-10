package com.github.reygnn.kolibri_launcher.ui.onboarding

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SelectableAppInfo

data class OnboardingUiState(
    val titleResId: Int = R.string.onboarding_title_welcome,
    val subtitleResId: Int = R.string.onboarding_subtitle_welcome,
    val selectableApps: List<SelectableAppInfo> = emptyList(),
    val selectedApps: List<AppInfo> = emptyList(),
    // First-run-only extras (set default launcher / restore backup). Gated to
    // INITIAL_SETUP in setLaunchMode so they never appear in the EDIT_FAVORITES
    // flow — nor in HiddenAppsActivity, which reuses this layout but never sets
    // this flag (the container's XML default is `gone`). Default false keeps
    // both reuse paths hidden without any extra wiring.
    val showSetupExtras: Boolean = false,
    // True while a backup restore is in flight (the async import runs for seconds
    // on a large ZIP). The Done + setup-extra buttons are disabled for this window
    // so a concurrent Done tap cannot run onDoneClicked and overwrite the
    // just-restored favorites with the empty in-memory selection.
    val isRestoring: Boolean = false
)