package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import javax.inject.Inject

/**
 * Persists the user's crash-report consent choice (and marks the dialog as
 * asked). Callers should run this on an app-lifetime scope
 * (`@ApplicationScope`) so the write survives the UI that triggered it —
 * see the consent dialog flow in `MainActivity` / `SettingsFragment`.
 */
class SetCrashReportConsentUseCase @Inject constructor(
    private val repository: CrashReportConsentRepository,
) {
    suspend operator fun invoke(consent: Boolean) {
        repository.setConsent(consent)
    }
}
