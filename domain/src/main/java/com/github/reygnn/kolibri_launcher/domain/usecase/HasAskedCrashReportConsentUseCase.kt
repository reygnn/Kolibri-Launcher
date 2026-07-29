package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import javax.inject.Inject

/**
 * Reports whether the consent dialog has already been shown once. The
 * launcher startup path uses it to decide whether to prompt: if the user
 * has been asked before, the stored consent already drives ACRA (set at
 * bootstrap) and no dialog is shown.
 */
class HasAskedCrashReportConsentUseCase @Inject constructor(
    private val repository: CrashReportConsentRepository,
) {
    suspend operator fun invoke(): Boolean = repository.hasAsked()
}
