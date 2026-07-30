package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.model.ConsentReadResult
import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCrashReportConsentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presentation-layer coordinator for the ACRA consent flow, shared by
 * `MainActivity` (first-launch prompt) and `SettingsFragment` (manual
 * toggle).
 *
 * It owns three responsibilities so the Android-runtime callers stay thin
 * glue (Rule 10):
 *  - the startup decision — [resolveStartupAction] returns whether to show
 *    the dialog, just re-affirm ACRA from the stored value, or do nothing
 *    because the store was unreadable (AUDIT-10 #3 / #2);
 *  - persistence — [persistConsent] runs the write on the injected
 *    app-lifetime [applicationScope] (`@ApplicationScope`, a
 *    `SupervisorJob`-backed scope), so it survives an immediate UI teardown
 *    after the tap (AUDIT-9 #11): structured concurrency under a known
 *    parent instead of the old detached `CoroutineScope(IO).launch`. The
 *    write is best-effort and its result is observed, not assumed — a failed
 *    persist is logged rather than left to diverge silently from the
 *    in-memory ACRA state (AUDIT-10 #11);
 *  - applying a decision — [applyConsent] / [reaffirmConsent] fold the
 *    persist + ACRA toggle + log sequence that used to be duplicated across
 *    both callers into one place (AUDIT-10 #12).
 *
 * The ACRA in-memory enable/disable goes through the injected
 * [CrashReportToggle] seam, which keeps the side effect in the app layer
 * (never `:domain`) while leaving the controller JVM-testable.
 */
@Singleton
class CrashReportConsentController @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    private val getConsent: GetCrashReportConsentUseCase,
    private val getState: GetCrashReportConsentStateUseCase,
    private val setConsent: SetCrashReportConsentUseCase,
    private val crashReportToggle: CrashReportToggle,
) {

    /** What the first-launch startup gate should do. */
    sealed interface StartupAction {
        /** Consent has never been answered — show the dialog. */
        data object ShowDialog : StartupAction

        /** Already answered; re-affirm ACRA from the stored [consent]. */
        data class Reaffirm(val consent: Boolean) : StartupAction

        /**
         * The stored state could not be read, so it is unknown whether the
         * user was ever asked. Do nothing this launch: leave ACRA on what the
         * bootstrap read established and try again next start (AUDIT-10 #2).
         */
        data object Skip : StartupAction
    }

    /**
     * Resolves the startup gate: if the dialog has already been answered,
     * carry the stored consent forward as [StartupAction.Reaffirm];
     * otherwise ask via [StartupAction.ShowDialog]. Lifting this decision
     * off the Activity keeps it unit-testable (AUDIT-10 #3).
     *
     * Reads both flags in one shot ([GetCrashReportConsentStateUseCase]),
     * so the common "already answered" start pays a single store read
     * instead of two (AUDIT-10 #4).
     *
     * A failed read yields [StartupAction.Skip], never [StartupAction.ShowDialog]:
     * the dialog branch writes back whatever the user taps, so treating an
     * unreadable store as "not asked yet" would let a transient I/O failure
     * overwrite a decision the user had already made (AUDIT-10 #2).
     */
    suspend fun resolveStartupAction(): StartupAction = when (val result = getState()) {
        is ConsentReadResult.Loaded ->
            if (result.state.hasAsked) {
                StartupAction.Reaffirm(result.state.hasConsent)
            } else {
                StartupAction.ShowDialog
            }

        is ConsentReadResult.Unavailable -> {
            Timber.w(
                result.cause,
                "Crash-report consent state unreadable; leaving ACRA as the bootstrap set it and re-reading next launch",
            )
            StartupAction.Skip
        }
    }

    /**
     * Applies a fresh user decision: switches ACRA to match (synchronous,
     * in-memory, cannot fail) and persists the choice on the app scope
     * (best-effort). Single source for the sequence both callers used to
     * duplicate (AUDIT-10 #12).
     *
     * The session is honoured immediately by the toggle; the persist is
     * fire-and-forget but no longer assumed to succeed — a failed write is
     * observed and logged in [persistConsent] rather than silently diverging
     * from the in-memory state (AUDIT-10 #11).
     */
    fun applyConsent(consent: Boolean) {
        crashReportToggle.setEnabled(consent)
        Timber.i("Crash-report consent applied: enabled = $consent")
        persistConsent(consent)
    }

    /**
     * Re-affirms ACRA from an already-stored decision without re-persisting.
     * Used by the startup gate when the dialog was answered on a previous
     * launch — cheap, and covers a bootstrap read that failed.
     */
    fun reaffirmConsent(consent: Boolean) {
        crashReportToggle.setEnabled(consent)
        Timber.i("Crash-report consent already answered; ACRA enabled = $consent")
    }

    /**
     * Persists [consent] on the app-lifetime scope. Fire-and-forget from
     * the caller's point of view, but structured: cancellation and failures
     * are owned by the app scope's `SupervisorJob`, not orphaned.
     *
     * The write is best-effort. Its [ConsentWriteResult] is inspected here
     * (not discarded): a [ConsentWriteResult.Failed] is logged so a persist
     * failure that leaves the store diverging from the just-applied in-memory
     * ACRA state is visible, not silent. The unset `hasAsked` self-heals via
     * re-ask on the next launch (AUDIT-10 #11).
     */
    fun persistConsent(consent: Boolean) {
        applicationScope.launch {
            when (val result = setConsent(consent)) {
                ConsentWriteResult.Saved -> Unit
                is ConsentWriteResult.Failed ->
                    Timber.w(
                        result.cause,
                        "Crash-report consent persist failed; " +
                            "in-memory ACRA already set to $consent, store will re-ask next launch",
                    )
            }
        }
    }

    /** The current stored consent flag (used to render the settings summary). */
    suspend fun currentConsent(): Boolean = getConsent()
}
