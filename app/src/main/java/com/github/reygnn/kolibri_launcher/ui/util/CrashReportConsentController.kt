package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HasAskedCrashReportConsentUseCase
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
 *    the dialog or just re-affirm ACRA from the stored value (AUDIT-10 #3);
 *  - persistence — [persistConsent] runs the write on the injected
 *    app-lifetime [applicationScope] (`@ApplicationScope`, a
 *    `SupervisorJob`-backed scope), so it survives an immediate UI teardown
 *    after the tap (AUDIT-9 #11): structured concurrency under a known
 *    parent instead of the old detached `CoroutineScope(IO).launch`;
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
    private val hasAsked: HasAskedCrashReportConsentUseCase,
    private val setConsent: SetCrashReportConsentUseCase,
    private val crashReportToggle: CrashReportToggle,
) {

    /** What the first-launch startup gate should do. */
    sealed interface StartupAction {
        /** Consent has never been answered — show the dialog. */
        data object ShowDialog : StartupAction

        /** Already answered; re-affirm ACRA from the stored [consent]. */
        data class Reaffirm(val consent: Boolean) : StartupAction
    }

    /**
     * Resolves the startup gate: if the dialog has already been answered,
     * carry the stored consent forward as [StartupAction.Reaffirm];
     * otherwise ask via [StartupAction.ShowDialog]. Lifting this decision
     * off the Activity keeps it unit-testable (AUDIT-10 #3).
     */
    suspend fun resolveStartupAction(): StartupAction =
        if (hasBeenAsked()) StartupAction.Reaffirm(currentConsent()) else StartupAction.ShowDialog

    /**
     * Applies a fresh user decision: persists it (on the app scope) and
     * switches ACRA to match. Single source for the sequence both callers
     * used to duplicate (AUDIT-10 #12).
     */
    fun applyConsent(consent: Boolean) {
        persistConsent(consent)
        crashReportToggle.setEnabled(consent)
        Timber.i("Crash-report consent applied: enabled = $consent")
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
     */
    fun persistConsent(consent: Boolean) {
        applicationScope.launch { setConsent(consent) }
    }

    /** Whether the consent dialog has already been answered once. */
    suspend fun hasBeenAsked(): Boolean = hasAsked()

    /** The current stored consent flag. */
    suspend fun currentConsent(): Boolean = getConsent()
}
