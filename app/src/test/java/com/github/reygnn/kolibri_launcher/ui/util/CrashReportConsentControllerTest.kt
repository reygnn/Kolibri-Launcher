package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HasAskedCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportConsentControllerTest {

    @get:Rule
    val timberRule = TimberRule()

    private val getConsent = mockk<GetCrashReportConsentUseCase>()
    private val hasAsked = mockk<HasAskedCrashReportConsentUseCase>()
    private val setConsent = mockk<SetCrashReportConsentUseCase>(relaxUnitFun = true)

    @Test
    fun `persistConsent defers the write onto the injected app scope`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent)

        controller.persistConsent(true)

        // StandardTestDispatcher is lazy: nothing ran inline on the caller.
        coVerify(exactly = 0) { setConsent(any()) }

        // Draining the injected scope's scheduler runs the launched write.
        advanceUntilIdle()
        coVerify(exactly = 1) { setConsent(true) }
    }

    @Test
    fun `persistConsent forwards the declined choice`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent)

        controller.persistConsent(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { setConsent(false) }
    }

    @Test
    fun `hasBeenAsked delegates to the use case`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent)
        coEvery { hasAsked() } returns true

        assertTrue(controller.hasBeenAsked())
    }

    @Test
    fun `currentConsent delegates to the use case`() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = CrashReportConsentController(appScope, getConsent, hasAsked, setConsent)
        coEvery { getConsent() } returns false

        assertFalse(controller.currentConsent())
    }
}
