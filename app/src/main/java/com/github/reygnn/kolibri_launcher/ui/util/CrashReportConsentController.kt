package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HasAskedCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCrashReportConsentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presentation-layer coordinator for the ACRA consent flow, shared by
 * `MainActivity` (first-launch prompt) and `SettingsFragment` (manual
 * toggle).
 *
 * Its whole reason to exist is [persistConsent]: the write runs on the
 * injected app-lifetime [applicationScope] (`@ApplicationScope`, a
 * `SupervisorJob`-backed scope), so it survives even if the Activity /
 * Fragment that triggered it is torn down immediately after the tap. This
 * replaces the detached `CoroutineScope(Dispatchers.IO).launch { … }` that
 * used to be created per button click inside the consent dialog
 * (AUDIT-9 #11): structured concurrency under a known parent instead of an
 * orphaned scope.
 *
 * The read queries ([hasBeenAsked], [currentConsent]) are plain suspend
 * pass-throughs to the use cases, exposed here so the UI never reaches into
 * the data layer for consent state.
 *
 * ACRA's in-memory enable/disable stays in the UI callers on purpose: it is
 * an app-layer side effect the domain must not know about.
 */
@Singleton
class CrashReportConsentController @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    private val getConsent: GetCrashReportConsentUseCase,
    private val hasAsked: HasAskedCrashReportConsentUseCase,
    private val setConsent: SetCrashReportConsentUseCase,
) {

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
