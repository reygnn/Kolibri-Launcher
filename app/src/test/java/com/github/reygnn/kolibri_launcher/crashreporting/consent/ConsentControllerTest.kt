package com.github.reygnn.kolibri_launcher.crashreporting.consent

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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Focused test for [ConsentController].
 *
 * The point of the controller is that [ConsentController.persistConsent] runs
 * the write on the injected app-lifetime scope rather than inline on the caller
 * — so persistence survives an immediate UI teardown. The scope is driven by a
 * [StandardTestDispatcher] (lazy), so the write is provably deferred until the
 * scheduler advances, and provably survives a caller scope cancelled before it
 * runs (A4).
 *
 * Also pins the startup decision ([ConsentController.resolveStartupAction],
 * incl. the Skip branch where an unreadable store must not re-ask, A2), the
 * apply sequence, and the revoke queue purge (A7). The ACRA toggle/purge and
 * the "could not be saved" toast go through faked [AcraToggle] /
 * [ConsentSaveFailureNotifier] seams, so those paths are JVM-verifiable without
 * ACRA or a Context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentControllerTest {

    @get:Rule
    val timberRule = TimberRule()

    private val repository = mockk<CrashReportConsentRepository>()
    private val acraToggle = mockk<AcraToggle>(relaxUnitFun = true)
    private val saveFailureNotifier = mockk<ConsentSaveFailureNotifier>(relaxUnitFun = true)

    @Before
    fun setup() {
        // Default the happy write path; failure-branch tests override locally.
        coEvery { repository.setConsent(any()) } returns ConsentWriteResult.Saved
    }

    private fun TestScope.buildController(): ConsentController =
        ConsentController(
            CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
            repository,
            acraToggle,
            saveFailureNotifier,
        )

    // ---------- persistConsent: deferred + survives teardown ----------

    @Test
    fun `persistConsent defers the write onto the injected app scope`() = runTest {
        val controller = buildController()

        controller.persistConsent(true)

        // StandardTestDispatcher is lazy: nothing ran inline on the caller.
        coVerify(exactly = 0) { repository.setConsent(any()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setConsent(true) }
    }

    @Test
    fun `persistConsent write survives cancellation of the calling scope`() = runTest {
        // Two scopes on the SAME scheduler but independent Jobs: caller stands
        // in for an Activity/Fragment lifecycle scope, appScope for the injected
        // @ApplicationScope.
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val controller = ConsentController(appScope, repository, acraToggle, saveFailureNotifier)

        callerScope.launch {
            controller.persistConsent(true)
            callerScope.cancel()
        }

        // The write completes only because it lives on the app scope, not the
        // (already cancelled) caller scope. A refactor moving the launch onto
        // the caller's scope would turn this red (A4).
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setConsent(true) }
    }

    @Test
    fun `persistConsent does not crash when the write reports Failed`() = runTest {
        val controller = buildController()
        coEvery { repository.setConsent(true) } returns ConsentWriteResult.Failed(IOException("edit failed"))

        controller.persistConsent(true)
        advanceUntilIdle()

        // The Failed result was consumed on the app scope without surfacing as
        // an uncaught exception (draining the scheduler would have rethrown one).
        coVerify(exactly = 1) { repository.setConsent(true) }
    }

    @Test
    fun `persistConsent tells the user when the write reports Failed`() = runTest {
        val controller = buildController()
        coEvery { repository.setConsent(false) } returns ConsentWriteResult.Failed(IOException("edit failed"))

        controller.persistConsent(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { saveFailureNotifier.notifySaveFailed() }
    }

    @Test
    fun `persistConsent stays silent when the write succeeds`() = runTest {
        val controller = buildController()

        controller.persistConsent(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { saveFailureNotifier.notifySaveFailed() }
    }

    @Test
    fun `persistConsent forwards the declined choice`() = runTest {
        val controller = buildController()

        controller.persistConsent(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.setConsent(false) }
    }

    // ---------- resolveStartupAction (tri-state) ----------

    @Test
    fun `resolveStartupAction shows the dialog when never asked`() = runTest {
        val controller = buildController()
        coEvery { repository.readState() } returns ConsentReadResult.Loaded(ConsentDecision.NeverAsked)

        assertEquals(ConsentController.StartupAction.ShowDialog, controller.resolveStartupAction())
    }

    @Test
    fun `resolveStartupAction re-affirms granted when the stored decision is Granted`() = runTest {
        val controller = buildController()
        coEvery { repository.readState() } returns ConsentReadResult.Loaded(ConsentDecision.Granted)

        assertEquals(ConsentController.StartupAction.Reaffirm(granted = true), controller.resolveStartupAction())
    }

    @Test
    fun `resolveStartupAction re-affirms denied when the stored decision is Denied`() = runTest {
        val controller = buildController()
        coEvery { repository.readState() } returns ConsentReadResult.Loaded(ConsentDecision.Denied)

        assertEquals(ConsentController.StartupAction.Reaffirm(granted = false), controller.resolveStartupAction())
    }

    @Test
    fun `resolveStartupAction skips the gate when the decision is unreadable`() = runTest {
        // An unreadable store must NOT be treated as "never asked": the dialog
        // branch writes back whatever the user taps, so a transient read failure
        // could otherwise overwrite a stored decision (A2).
        val controller = buildController()
        coEvery { repository.readState() } returns ConsentReadResult.Unavailable(IOException("read failed"))

        assertEquals(ConsentController.StartupAction.Skip, controller.resolveStartupAction())
    }

    @Test
    fun `resolveStartupAction leaves ACRA untouched when the decision is unreadable`() = runTest {
        val controller = buildController()
        coEvery { repository.readState() } returns ConsentReadResult.Unavailable(IOException("read failed"))

        controller.resolveStartupAction()
        advanceUntilIdle()

        verify(exactly = 0) { acraToggle.setEnabled(any()) }
        coVerify(exactly = 0) { repository.setConsent(any()) }
    }

    @Test
    fun `resolveStartupAction reads the decision only once`() = runTest {
        val controller = buildController()
        coEvery { repository.readState() } returns ConsentReadResult.Loaded(ConsentDecision.Denied)

        controller.resolveStartupAction()

        coVerify(exactly = 1) { repository.readState() }
    }

    // ---------- applyConsent / reaffirmConsent (+ A7 purge) ----------

    @Test
    fun `applyConsent persists the decision and switches ACRA to match`() = runTest {
        val controller = buildController()

        controller.applyConsent(true)

        verify(exactly = 1) { acraToggle.setEnabled(true) }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setConsent(true) }
    }

    @Test
    fun `applyConsent still switches ACRA when the persist fails`() = runTest {
        val controller = buildController()
        coEvery { repository.setConsent(true) } returns ConsentWriteResult.Failed(IOException("edit failed"))

        controller.applyConsent(true)

        verify(exactly = 1) { acraToggle.setEnabled(true) }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setConsent(true) }
    }

    @Test
    fun `applyConsent false purges the unsent report queue`() = runTest {
        // Revoke is destructive: reports created during the consent phase must
        // not drain after a later re-consent (A7).
        val controller = buildController()

        controller.applyConsent(false)

        verify(exactly = 1) { acraToggle.setEnabled(false) }
        verify(exactly = 1) { acraToggle.purgeReportQueue() }
    }

    @Test
    fun `applyConsent true does not purge the queue`() = runTest {
        val controller = buildController()

        controller.applyConsent(true)

        verify(exactly = 0) { acraToggle.purgeReportQueue() }
    }

    @Test
    fun `reaffirmConsent switches ACRA without persisting or purging`() = runTest {
        val controller = buildController()

        controller.reaffirmConsent(false)
        advanceUntilIdle()

        verify(exactly = 1) { acraToggle.setEnabled(false) }
        verify(exactly = 0) { acraToggle.purgeReportQueue() }
        coVerify(exactly = 0) { repository.setConsent(any()) }
    }

    @Test
    fun `currentDecision returns the stored read result`() = runTest {
        val controller = buildController()
        val expected = ConsentReadResult.Loaded(ConsentDecision.Denied)
        coEvery { repository.readState() } returns expected

        assertEquals(expected, controller.currentDecision())
    }
}
