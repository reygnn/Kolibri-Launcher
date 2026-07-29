package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import javax.inject.Inject

/**
 * Reads the current ACRA crash-report consent flag. Used to render the
 * settings summary (enabled/disabled) after the user changes it.
 */
class GetCrashReportConsentUseCase @Inject constructor(
    private val repository: CrashReportConsentRepository,
) {
    suspend operator fun invoke(): Boolean = repository.hasConsent()
}
