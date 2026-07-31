package com.github.reygnn.kolibri_launcher.crashreporting.consent

import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presentation-layer coordinator for the ACRA consent flow, shared by
 * `MainActivity` (first-launch prompt) and `SettingsFragment` (manual toggle).
 *
 * It owns three responsibilities so the Android-runtime callers stay thin glue
 * (Rule 10):
 *  - the startup decision — [resolveStartupAction] maps the stored
 *    [ConsentDecision] to show the dialog, re-affirm ACRA, or do nothing
 *    because the store was unreadable (A2);
 *  - persistence — [persistConsent] runs the write on the injected
 *    app-lifetime [applicationScope] (`@ApplicationScope`, a `SupervisorJob`
 *    scope), so it survives an immediate UI teardown after the tap. The write
 *    is best-effort and its result is observed, not assumed — a failed persist
 *    is logged and shown to the user rather than left to diverge silently from
 *    the in-memory ACRA state (A4);
 *  - applying a decision — [applyConsent] / [reaffirmConsent] fold the persist
 *    + ACRA toggle (+ revoke purge) sequence into one place.
 *
 * The ACRA in-memory enable/disable and the revoke queue purge go through the
 * injected [AcraToggle] seam, keeping the side effect in the app layer (never
 * `:domain`) while leaving the controller JVM-testable. Depends only on the
 * [CrashReportConsentRepository] interface (Rule 1), no concrete impl.
 */
@Singleton
class ConsentController @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    private val repository: CrashReportConsentRepository,
    private val acraToggle: AcraToggle,
    private val saveFailureNotifier: ConsentSaveFailureNotifier,
) {

    /** What the first-launch startup gate should do. */
    sealed interface StartupAction {
        /** Consent has never been answered — show the dialog. */
        data object ShowDialog : StartupAction

        /** Already answered; re-affirm ACRA from the stored [granted]. */
        data class Reaffirm(val granted: Boolean) : StartupAction

        /**
         * The stored decision could not be read. Do nothing this launch: leave
         * ACRA on what the bootstrap read established and try again next start
         * (A2).
         */
        data object Skip : StartupAction
    }

    /**
     * Resolves the startup gate from the single tri-state read. A failed read
     * yields [StartupAction.Skip], never [StartupAction.ShowDialog]: the dialog
     * branch writes back whatever the user taps, so treating an unreadable
     * store as "not asked yet" would let a transient I/O failure overwrite a
     * decision the user had already made (A2).
     */
    suspend fun resolveStartupAction(): StartupAction = when (val result = repository.readState()) {
        is ConsentReadResult.Loaded -> when (result.decision) {
            ConsentDecision.NeverAsked -> StartupAction.ShowDialog
            ConsentDecision.Granted -> StartupAction.Reaffirm(granted = true)
            ConsentDecision.Denied -> StartupAction.Reaffirm(granted = false)
        }

        is ConsentReadResult.Unavailable -> {
            Timber.w(
                result.cause,
                "Crash-report consent unreadable; leaving ACRA as the bootstrap set it and re-reading next launch",
            )
            StartupAction.Skip
        }
    }

    /**
     * Applies a fresh user decision: switches ACRA to match (synchronous,
     * in-memory, cannot fail), purges the unsent queue on a revoke (A7), and
     * persists the choice on the app scope (best-effort). Single source for the
     * sequence both callers share.
     */
    fun applyConsent(granted: Boolean) {
        acraToggle.setEnabled(granted)
        if (!granted) {
            // Revoke is destructive: drop reports created during the consent
            // phase so they cannot drain after a later re-consent (A7).
            acraToggle.purgeReportQueue()
        }
        Timber.i("Crash-report consent applied: enabled = $granted")
        persistConsent(granted)
    }

    /**
     * Re-affirms ACRA from an already-stored decision without re-persisting.
     * Used by the startup gate when the dialog was answered on a previous
     * launch — idempotent, and covers a bootstrap read that failed. No purge:
     * an "R1 on, R2 denied" divergence is not reachable (RC2), so nothing was
     * enabled to revoke.
     */
    fun reaffirmConsent(granted: Boolean) {
        acraToggle.setEnabled(granted)
        Timber.i("Crash-report consent already answered; ACRA enabled = $granted")
    }

    /**
     * Persists [granted] on the app-lifetime scope. Fire-and-forget from the
     * caller's point of view, but structured under the app scope's
     * `SupervisorJob`. The [ConsentWriteResult] is inspected (not discarded): a
     * [ConsentWriteResult.Failed] is logged AND surfaced to the user via
     * [ConsentSaveFailureNotifier] — nothing was persisted, so the previous
     * stored decision keeps winning next launch and the confirmation toast
     * would otherwise be the only (misleading) feedback (A4).
     */
    fun persistConsent(granted: Boolean) {
        applicationScope.launch {
            when (val result = repository.setConsent(granted)) {
                ConsentWriteResult.Saved -> Unit
                is ConsentWriteResult.Failed -> {
                    Timber.w(
                        result.cause,
                        "Crash-report consent persist failed; in-memory ACRA set to $granted " +
                            "for this session, store keeps its previous decision",
                    )
                    saveFailureNotifier.notifySaveFailed()
                }
            }
        }
    }

    /** The current stored decision (used to render the settings summary). */
    suspend fun currentDecision(): ConsentReadResult = repository.readState()
}
