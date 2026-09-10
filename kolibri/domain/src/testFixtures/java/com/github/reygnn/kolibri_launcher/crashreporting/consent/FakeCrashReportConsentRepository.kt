package com.github.reygnn.kolibri_launcher.crashreporting.consent

/**
 * In-memory [CrashReportConsentRepository] test double. Backed by a single
 * [ConsentDecision] (the tri-state source of truth); [setConsent] mirrors the
 * impl by mapping the boolean to [ConsentDecision.Granted] /
 * [ConsentDecision.Denied] and always reports [ConsentWriteResult.Saved],
 * [readState] always [ConsentReadResult.Loaded]. Never fails — the
 * [ConsentReadResult.Unavailable] / [ConsentWriteResult.Failed] branches are
 * impl-only I/O detail, pinned by `CrashReportConsentRepositoryImplTest`, not
 * the contract.
 */
class FakeCrashReportConsentRepository : CrashReportConsentRepository {

    var decision: ConsentDecision = ConsentDecision.NeverAsked

    override suspend fun readState(): ConsentReadResult = ConsentReadResult.Loaded(decision)

    override suspend fun setConsent(granted: Boolean): ConsentWriteResult {
        decision = if (granted) ConsentDecision.Granted else ConsentDecision.Denied
        return ConsentWriteResult.Saved
    }
}
