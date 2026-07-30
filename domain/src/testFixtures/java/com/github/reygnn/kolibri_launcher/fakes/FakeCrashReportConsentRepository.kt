package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.domain.model.CrashReportConsentState
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository

/**
 * In-memory [CrashReportConsentRepository] test double. Backed by two plain
 * booleans; [setConsent] mirrors the impl by flipping `asked` to true on
 * every write and always reports [ConsentWriteResult.Saved]. Never fails —
 * the fail-closed/[ConsentWriteResult.Failed] behaviour is impl-only I/O
 * detail, pinned by `CrashReportConsentRepositoryImplTest`, not the contract.
 */
class FakeCrashReportConsentRepository : CrashReportConsentRepository {

    var consent: Boolean = false
    var asked: Boolean = false

    override suspend fun hasConsent(): Boolean = consent

    override suspend fun hasAsked(): Boolean = asked

    override suspend fun readState(): CrashReportConsentState =
        CrashReportConsentState(hasConsent = consent, hasAsked = asked)

    override suspend fun setConsent(consent: Boolean): ConsentWriteResult {
        this.consent = consent
        this.asked = true
        return ConsentWriteResult.Saved
    }
}
