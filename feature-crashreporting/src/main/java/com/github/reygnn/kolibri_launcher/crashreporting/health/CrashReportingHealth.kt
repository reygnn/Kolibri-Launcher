package com.github.reygnn.kolibri_launcher.crashreporting.health

import androidx.annotation.VisibleForTesting
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentReadResult
import com.github.reygnn.kolibri_launcher.crashreporting.consent.CrashReportConsentRepository
import javax.inject.Inject

/**
 * Out-of-band health signal for the ACRA bootstrap consent gate. Process-scoped,
 * re-derived on every cold start.
 *
 * The signal is deliberately NOT "is ACRA enabled right now": the
 * `MainActivity.reaffirmConsent` net re-enables ACRA at runtime for a Granted
 * user REGARDLESS of whether the bootstrap gate worked, so an "enabled now" check
 * would mask exactly the failure class we want to catch (the 2026-08 cold-start
 * NPE, where the bootstrap gate died and only the reaffirm net kept runtime
 * reporting alive — but the ANR drain, the early window and the unified handler
 * were defeated). So the health flag tracks GATE COMPLETION instead.
 *
 * NEVER reported through ACRA — that would be circular (the thing we detect is
 * ACRA being dead). The consumer surfaces it via a notification + a Settings hint.
 */
object CrashReportingHealth {
    // Default unhealthy: only a fully-completed onCreate consent gate flips it.
    @Volatile
    private var bootstrapGateCompleted = false

    /** Called by `CrashReportingBootstrap.onCreate` iff the consent gate finished. */
    fun markBootstrapGateCompleted() {
        bootstrapGateCompleted = true
    }

    val isBootstrapHealthy: Boolean get() = bootstrapGateCompleted

    /** The process-scoped flag is set once per boot; tests reset between cases. */
    @VisibleForTesting
    fun resetForTest() {
        bootstrapGateCompleted = false
    }
}

/** The three verdicts the UI reflects, plus [UNKNOWN] for an unreadable consent store. */
enum class CrashReportingHealthState {
    /** Consent Granted and the bootstrap gate completed — ACRA is healthy. */
    HEALTHY,

    /** Consent Granted but the bootstrap gate did NOT complete — ACRA is degraded. */
    BROKEN,

    /** Consent not Granted — ACRA is correctly off, no health claim applies. */
    NOT_APPLICABLE,

    /** Consent store unreadable — cannot conclude anything. */
    UNKNOWN,
}

/**
 * Decides the [CrashReportingHealthState] from consent + the bootstrap health flag.
 * Pure decision logic (the notification side effect lives in
 * `CrashReportingHealthNotifier`), so it is unit-testable across the consent ×
 * health matrix via the injected [CrashReportConsentRepository] and the
 * `isBootstrapHealthy` seam.
 */
class CrashReportingHealthMonitor @Inject constructor(
    private val consentRepository: CrashReportConsentRepository,
) {
    suspend fun evaluate(
        isBootstrapHealthy: () -> Boolean = { CrashReportingHealth.isBootstrapHealthy },
    ): CrashReportingHealthState =
        when (val result = consentRepository.readState()) {
            is ConsentReadResult.Loaded ->
                when {
                    result.decision != ConsentDecision.Granted -> CrashReportingHealthState.NOT_APPLICABLE
                    isBootstrapHealthy() -> CrashReportingHealthState.HEALTHY
                    else -> CrashReportingHealthState.BROKEN
                }

            is ConsentReadResult.Unavailable -> CrashReportingHealthState.UNKNOWN
        }
}
