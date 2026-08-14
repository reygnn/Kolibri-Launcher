package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.app.Application
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrReporter
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the X2 process gate AND every main-process side effect of
 * [CrashReportingBootstrap.onCreate]. The `:acra` sender process must run
 * NOTHING (a fresh ANR's watermark advanced past without a live reporter is a
 * silent loss + cross-process DataStore write); the main process must plant the
 * single delivery tree, drain post-mortem ANRs, and start the recovery watchdog.
 *
 * All three side effects are injected seams, so each is observed directly and a
 * deleted line goes red on its own counter — no real Timber forest, no real
 * watchdog thread, so this is a pure-JVM test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportingBootstrapProcessGateTest {

    private val app = mockk<Application>(relaxed = true)
    private val anrReporter = mockk<AnrReporter>(relaxed = true)
    private var treePlants = 0
    private var watchdogStarts = 0
    private val enableCalls = mutableListOf<Boolean>()
    private var consentReads = 0

    private fun runOnCreate(sender: Boolean, scope: CoroutineScope, granted: Boolean = false) {
        CrashReportingBootstrap.onCreate(
            app,
            scope,
            anrReporter,
            isSenderProcess = { sender },
            plantDeliveryTree = { treePlants++ },
            startWatchdog = { watchdogStarts++ },
            // Consent-gate seams injected too — otherwise the default readDecision
            // hits a real DataStore on the mock Application. These also let this
            // test pin the X2 gate over the consent read (moved into onCreate).
            setEnabled = { enableCalls += it },
            readDecision = {
                consentReads++
                if (granted) ConsentDecision.Granted else ConsentDecision.Denied
            },
        )
    }

    @Test
    fun `sender process runs none of the onCreate wiring`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

        runOnCreate(sender = true, scope)
        advanceUntilIdle()

        coVerify(exactly = 0) { anrReporter.reportPendingAnrs(any()) }
        assertEquals(0, treePlants)
        assertEquals(0, watchdogStarts)
        // X2: the sender process must NOT read consent or toggle ACRA.
        assertEquals(0, consentReads)
        assertEquals(emptyList<Boolean>(), enableCalls)
    }

    @Test
    fun `main process gates consent, plants the tree, drains ANRs and starts the watchdog`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

        runOnCreate(sender = false, scope, granted = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { anrReporter.reportPendingAnrs(any()) }
        assertEquals(1, treePlants)
        assertEquals(1, watchdogStarts)
        // The consent gate now runs in onCreate: read once, and A1 disable-then-
        // enable for a Granted decision. This is the fix's core — it must run here,
        // not attachBaseContext, where applicationContext is null.
        assertEquals(1, consentReads)
        assertEquals(listOf(false, true), enableCalls)
    }
}
