package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.CrashReportConsentState
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import javax.inject.Inject

/**
 * Reads the crash-report consent state (consent + asked) in one shot. The
 * launcher startup gate uses it to decide "show dialog vs. re-affirm"
 * without a second read of the same store (AUDIT-10 #4).
 */
class GetCrashReportConsentStateUseCase @Inject constructor(
    private val repository: CrashReportConsentRepository,
) {
    suspend operator fun invoke(): CrashReportConsentState = repository.readState()
}
