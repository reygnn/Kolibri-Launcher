package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import javax.inject.Inject

/**
 * Persists the user's crash-report consent choice (and marks the dialog as
 * asked). Callers should run this on an app-lifetime scope
 * (`@ApplicationScope`) so the write survives the UI that triggered it —
 * see the consent dialog flow in `MainActivity` / `SettingsFragment`.
 *
 * Forwards the repository's [ConsentWriteResult] so an app-scope caller can
 * observe a persist failure instead of assuming success (AUDIT-10 #11).
 */
class SetCrashReportConsentUseCase @Inject constructor(
    private val repository: CrashReportConsentRepository,
) {
    suspend operator fun invoke(consent: Boolean): ConsentWriteResult =
        repository.setConsent(consent)
}
