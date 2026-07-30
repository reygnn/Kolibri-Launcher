package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.ConsentReadResult
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import javax.inject.Inject

/**
 * Reads the crash-report consent state (consent + asked) in one shot. The
 * launcher startup gate uses it to decide "show dialog vs. re-affirm"
 * without a second read of the same store (AUDIT-10 #4).
 *
 * Forwards the repository's [ConsentReadResult] unchanged, so the gate can
 * tell an unreadable store from a stored "not asked yet" — the two must not
 * lead to the same action (AUDIT-10 #2).
 */
class GetCrashReportConsentStateUseCase @Inject constructor(
    private val repository: CrashReportConsentRepository,
) {
    suspend operator fun invoke(): ConsentReadResult = repository.readState()
}
