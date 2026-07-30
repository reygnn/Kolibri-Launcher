package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.domain.model.ConsentReadResult
import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.domain.model.CrashReportConsentState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentUseCase
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

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
 * AUDIT-10 #3 — including the third branch, where an unreadable store must
 * skip the gate instead of re-asking, AUDIT-10 #2) and the apply sequence ([CrashReportConsentController.applyConsent] /
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
    private val getState = mockk<GetCrashReportConsentStateUseCase>()
    private val setConsent = mockk<SetCrashReportConsentUseCase>()
    private val crashReportToggle = mockk<CrashReportToggle>(relaxUnitFun = true)

    @Before
    fun setup() {
        // setConsent now returns a ConsentWriteResult; default the happy path.
        // Tests that exercise the failure branch override this locally.
        coEvery { setConsent(any()) } returns ConsentWriteResult.Saved
    }

    @Test
    fun `persistConsent defers the write onto the injected app scope`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)

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
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)

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
    fun `persistConsent does not crash when the write reports Failed`() = runTest {
        // A best-effort persist that fails must be observed and logged, not
        // rethrown into the app scope or assumed successful (AUDIT-10 #11).
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { setConsent(true) } returns ConsentWriteResult.Failed(IOException("edit failed"))

        controller.persistConsent(true)
        advanceUntilIdle()

        // The write was attempted and the Failed result was consumed on the
        // app scope without surfacing as an uncaught exception (draining the
        // scheduler above would have rethrown one).
        coVerify(exactly = 1) { setConsent(true) }
    }

    @Test
    fun `applyConsent still switches ACRA when the persist fails`() = runTest {
        // The in-memory toggle is synchronous and independent of the write, so
        // a failed persist must not stop ACRA from reflecting the session's
        // choice (AUDIT-10 #11).
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { setConsent(true) } returns ConsentWriteResult.Failed(IOException("edit failed"))

        controller.applyConsent(true)

        verify(exactly = 1) { crashReportToggle.setEnabled(true) }
        advanceUntilIdle()
        coVerify(exactly = 1) { setConsent(true) }
    }

    @Test
    fun `persistConsent forwards the declined choice`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)

        controller.persistConsent(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { setConsent(false) }
    }

    @Test
    fun `resolveStartupAction shows the dialog when never asked`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { getState() } returns ConsentReadResult.Loaded(CrashReportConsentState(hasConsent = false, hasAsked = false))

        assertEquals(
            CrashReportConsentController.StartupAction.ShowDialog,
            controller.resolveStartupAction()
        )
    }

    @Test
    fun `resolveStartupAction re-affirms the stored consent when already asked`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { getState() } returns ConsentReadResult.Loaded(CrashReportConsentState(hasConsent = true, hasAsked = true))

        assertEquals(
            CrashReportConsentController.StartupAction.Reaffirm(true),
            controller.resolveStartupAction()
        )
    }

    @Test
    fun `resolveStartupAction skips the gate when the state is unreadable`() = runTest {
        // An unreadable store must NOT be treated as "never asked": the dialog
        // branch writes back whatever the user taps, so a transient read
        // failure could otherwise overwrite a stored decision (AUDIT-10 #2).
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { getState() } returns ConsentReadResult.Unavailable(IOException("read failed"))

        assertEquals(
            CrashReportConsentController.StartupAction.Skip,
            controller.resolveStartupAction()
        )
    }

    @Test
    fun `resolveStartupAction leaves ACRA untouched when the state is unreadable`() = runTest {
        // Skip means "do nothing": ACRA keeps whatever the bootstrap read
        // established, and nothing is persisted (AUDIT-10 #2).
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { getState() } returns ConsentReadResult.Unavailable(IOException("read failed"))

        controller.resolveStartupAction()
        advanceUntilIdle()

        verify(exactly = 0) { crashReportToggle.setEnabled(any()) }
        coVerify(exactly = 0) { setConsent(any()) }
    }

    @Test
    fun `resolveStartupAction reads the consent state only once`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { getState() } returns ConsentReadResult.Loaded(CrashReportConsentState(hasConsent = false, hasAsked = true))

        controller.resolveStartupAction()

        // Single combined read — no separate hasAsked + getConsent (AUDIT-10 #4).
        coVerify(exactly = 1) { getState() }
        coVerify(exactly = 0) { getConsent() }
    }

    @Test
    fun `applyConsent persists the decision and switches ACRA to match`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)

        controller.applyConsent(true)

        // ACRA is switched synchronously; the persist is deferred onto the app scope.
        verify(exactly = 1) { crashReportToggle.setEnabled(true) }
        advanceUntilIdle()
        coVerify(exactly = 1) { setConsent(true) }
    }

    @Test
    fun `reaffirmConsent switches ACRA without persisting`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)

        controller.reaffirmConsent(false)
        advanceUntilIdle()

        verify(exactly = 1) { crashReportToggle.setEnabled(false) }
        coVerify(exactly = 0) { setConsent(any()) }
    }

    @Test
    fun `currentConsent delegates to the use case`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, getState, setConsent, crashReportToggle)
        coEvery { getConsent() } returns false

        assertFalse(controller.currentConsent())
    }
}
