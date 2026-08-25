package com.github.reygnn.kolibri_launcher.ui.onboarding

import androidx.annotation.StringRes

sealed class OnboardingEvent {
    // messageResId (not a raw String) so the Activity resolves a localized
    // string (AUDIT-18 F1) — the errors used to ship hardcoded English.
    data class ShowError(@param:StringRes val messageResId: Int) : OnboardingEvent()
    object NavigateToMain : OnboardingEvent()
    data class ShowLimitReachedToast(val limit: Int) : OnboardingEvent()
    // Restore succeeded but the backup referenced [count] apps that aren't
    // installed on this device (they were skipped). Surfaced as a short toast.
    data class ShowMissingAppsToast(val count: Int) : OnboardingEvent()
    // Restore succeeded but [count] wallpaper layers couldn't be restored (their
    // source was no longer accessible). Surfaced so a partial wallpaper restore is
    // never silent (BackupData.ImportResult.Success KDoc), mirroring the Settings
    // restore flow. Short toast.
    data class ShowDroppedLayersToast(val count: Int) : OnboardingEvent()
}
