package com.github.reygnn.kolibri_launcher.ui.onboarding

sealed class OnboardingEvent {
    data class ShowError(val message: String) : OnboardingEvent()
    object NavigateToMain : OnboardingEvent()
    data class ShowLimitReachedToast(val limit: Int) : OnboardingEvent()
}