package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HasAskedCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Focused test for [CrashReportConsentController].
 *
 * The point of the controller is that [CrashReportConsentController.persistConsent]
 * runs the write on the injected app-lifetime scope rather than inline on the
 * caller — so the persistence survives an immediate UI teardown (the whole
 * reason the old detached `CoroutineScope(IO).launch` was replaced, AUDIT-9
 * #11). The scope is driven by a [StandardTestDispatcher], which is lazy, so
 * the write is provably deferred until the scope's scheduler advances.
 *
 * Two properties are pinned: that the write is *deferred* onto the injected
 * scope, and — the actual reason the controller exists — that it *survives*
 * a caller scope that is cancelled before the write runs. The latter is the
 * one a naive test misses: without a cancelled caller scope in play, a
 * refactor that moved the launch back onto the caller's lifecycle scope
 * would stay green (AUDIT-10 #7).
 *
 * Also pins the startup decision ([CrashReportConsentController.resolveStartupAction],
 * AUDIT-10 #3) and the apply sequence ([CrashReportConsentController.applyConsent] /
 * [CrashReportConsentController.reaffirmConsent], AUDIT-10 #12) now that both
 * live on the controller instead of the Android-runtime callers. The ACRA
 * toggle goes through a faked [CrashReportToggle], so those paths are
 * JVM-verifiable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportConsentControllerTest {

    @get:Rule
    val timberRule = TimberRule()

    private val getConsent = mockk<GetCrashReportConsentUseCase>()
    private val hasAsked = mockk<HasAskedCrashReportConsentUseCase>()
    private val setConsent = mockk<SetCrashReportConsentUseCase>(relaxUnitFun = true)
    private val crashReportToggle = mockk<CrashReportToggle>(relaxUnitFun = true)

    @Test
    fun `persistConsent defers the write onto the injected app scope`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)

        controller.persistConsent(true)

        // StandardTestDispatcher is lazy: nothing ran inline on the caller.
        coVerify(exactly = 0) { setConsent(any()) }

        // Draining the injected scope's scheduler runs the launched write.
        advanceUntilIdle()
        coVerify(exactly = 1) { setConsent(true) }
    }

    @Test
    fun `persistConsent write survives cancellation of the calling scope`() = runTest {
        // Two scopes on the SAME scheduler but with independent Jobs: the
        // caller scope stands in for an Activity/Fragment lifecycle scope,
        // the app scope for the injected @ApplicationScope.
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)

        // The UI triggers the persist from its own lifecycle scope and is torn
        // down in the very same turn — persistConsent launches the write on the
        // app scope, then the caller scope is cancelled before the scheduler
        // gets to run that write.
        callerScope.launch {
            controller.persistConsent(true)
            callerScope.cancel()
        }

        // Draining the shared scheduler now runs the write. It completes only
        // because it lives on the app scope, not the (already cancelled) caller
        // scope. If a refactor moved the launch onto the caller's scope, the
        // cancel above would kill the pending write and setConsent would never
        // run — turning this red. That is the regression the controller exists
        // to prevent (AUDIT-9 #11 / AUDIT-10 #7).
        advanceUntilIdle()
        coVerify(exactly = 1) { setConsent(true) }
    }

    @Test
    fun `persistConsent forwards the declined choice`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)

        controller.persistConsent(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { setConsent(false) }
    }

    @Test
    fun `resolveStartupAction shows the dialog when never asked`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)
        coEvery { hasAsked() } returns false

        assertEquals(
            CrashReportConsentController.StartupAction.ShowDialog,
            controller.resolveStartupAction()
        )
    }

    @Test
    fun `resolveStartupAction re-affirms the stored consent when already asked`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)
        coEvery { hasAsked() } returns true
        coEvery { getConsent() } returns true

        assertEquals(
            CrashReportConsentController.StartupAction.Reaffirm(true),
            controller.resolveStartupAction()
        )
    }

    @Test
    fun `applyConsent persists the decision and switches ACRA to match`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)

        controller.applyConsent(true)

        // ACRA is switched synchronously; the persist is deferred onto the app scope.
        verify(exactly = 1) { crashReportToggle.setEnabled(true) }
        advanceUntilIdle()
        coVerify(exactly = 1) { setConsent(true) }
    }

    @Test
    fun `reaffirmConsent switches ACRA without persisting`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)

        controller.reaffirmConsent(false)
        advanceUntilIdle()

        verify(exactly = 1) { crashReportToggle.setEnabled(false) }
        coVerify(exactly = 0) { setConsent(any()) }
    }

    @Test
    fun `hasBeenAsked delegates to the use case`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)
        coEvery { hasAsked() } returns true

        assertTrue(controller.hasBeenAsked())
    }

    @Test
    fun `currentConsent delegates to the use case`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent, crashReportToggle)
        coEvery { getConsent() } returns false

        assertFalse(controller.currentConsent())
    }
}
