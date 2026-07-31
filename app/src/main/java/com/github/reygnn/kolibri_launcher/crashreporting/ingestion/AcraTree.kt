package com.github.reygnn.kolibri_launcher.crashreporting.ingestion

import android.util.Log
import org.acra.ACRA
import timber.log.Timber

/**
 * The one delivery path for logged errors, ANRs and watchdog stalls (B2):
 * `Timber.e/w(t)` at WARN+ with a throwable → a per-report carrier
 * ([buildAcraReportThrowable]) → `handleSilentException`.
 *
 * No client-side throttling (B3): every consent-gated report is sent, and
 * flood control (fingerprint dedup + ingestion rate-limit) lives entirely on
 * the server. No consent check (B1): `handleSilentException` is a no-op while
 * ACRA is disabled, so the `enabled` flag is the single privacy gate.
 *
 * The swallow uses `android.util.Log.e`, NOT `Timber` — `AcraTree` *is* a
 * Timber tree, so a `Timber.e` in its own `catch` would re-enter [log]
 * (infinite recursion). It is also NOT `silentError`: a DEBUG throw inside the
 * delivery path would land right back in the path it is meant to be the safety
 * net for (crash-infra, Rule 9/C1). Hence `AcraTree` needs no Rule-9 whitelist
 * entry — it uses no bare `Timber.e`.
 *
 * [deliver] is a seam over `ACRA.errorReporter.handleSilentException` (default
 * production wiring) so the gate/swallow are JVM-testable without the ACRA
 * singleton — same pattern as [com.github.reygnn.kolibri_launcher.crashreporting.consent.AcraToggle].
 */
internal class AcraTree(
    private val deliver: (Throwable) -> Unit = { ACRA.errorReporter.handleSilentException(it) },
) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Gate: only WARN+ with a throwable. No consent check — the ACRA enabled
        // flag gates (B1); no throttle step — flood control is server-side (B3).
        if (priority < Log.WARN || t == null) {
            return
        }
        try {
            // Fold the log context + CancellationException diagnosis into a
            // per-report carrier throwable (no process-global custom-data map,
            // B4). The original throwable rides along as the cause.
            deliver(buildAcraReportThrowable(priority, tag, message, t))
        } catch (t2: Throwable) {
            // C1: swallow. Log.e — NOT Timber (self-recursion), NOT silentError
            // (crash-infra, no DEBUG throw in the delivery path).
            Log.e("AcraTree", "Failed to enqueue report to ACRA", t2)
        }
    }
}
