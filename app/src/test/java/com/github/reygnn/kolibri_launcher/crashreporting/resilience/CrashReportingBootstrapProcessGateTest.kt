package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.app.Application
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

    private fun runOnCreate(sender: Boolean, scope: CoroutineScope) {
        CrashReportingBootstrap.onCreate(
            app,
            scope,
            anrReporter,
            isSenderProcess = { sender },
            plantDeliveryTree = { treePlants++ },
            startWatchdog = { watchdogStarts++ },
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
    }

    @Test
    fun `main process plants the tree, drains ANRs and starts the watchdog`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

        runOnCreate(sender = false, scope)
        advanceUntilIdle()

        coVerify(exactly = 1) { anrReporter.reportPendingAnrs(any()) }
        assertEquals(1, treePlants)
        assertEquals(1, watchdogStarts)
    }
}
