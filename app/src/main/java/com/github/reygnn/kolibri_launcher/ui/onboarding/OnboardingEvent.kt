package com.github.reygnn.kolibri_launcher.ui.onboarding

import androidx.annotation.StringRes

sealed class OnboardingEvent {
    // messageResId (not a raw String) so the Activity resolves a localized
    // string (AUDIT-18 F1) — the errors used to ship hardcoded English.
    data class ShowError(@param:StringRes val messageResId: Int) : OnboardingEvent()
    object NavigateToMain : OnboardingEvent()
    data class ShowLimitReachedToast(val limit: Int) : OnboardingEvent()
}
