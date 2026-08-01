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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

/**
 * Pins the X2 process gate on [CrashReportingBootstrap.onCreate]: the `:acra`
 * sender process must NOT run the post-mortem ANR drain. The drain advances the
 * settings-DataStore watermark that only the main process owns, so a second
 * writer in `:acra` is a cross-process race that stamps a fresh ANR as
 * "reported" without a live reporter → the main process then filters it out and
 * the ANR is lost. The gate is symmetric with the one attachBaseContext already
 * has on its consent read.
 *
 * The process verdict is an injected predicate, so the gate is verifiable
 * without the static `ACRA.isACRASenderServiceProcess()`. Robolectric because
 * the main-process branch constructs a `Handler(Looper.getMainLooper())` for the
 * watchdog post (the posted runnable stays queued on the paused looper and never
 * runs, so no real watchdog starts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CrashReportingBootstrapProcessGateTest {

    private val app = mockk<Application>(relaxed = true)
    private val anrReporter = mockk<AnrReporter>(relaxed = true)

    @After
    fun tearDown() {
        // onCreate plants AcraTree in the main-process branch; keep the global
        // Timber forest clean for other tests.
        Timber.uprootAll()
    }

    @Test
    fun `sender process skips the ANR drain`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

        CrashReportingBootstrap.onCreate(app, scope, anrReporter, isSenderProcess = { true })
        advanceUntilIdle()

        // A regression that drops the gate (running the drain in `:acra`) turns
        // this red.
        coVerify(exactly = 0) { anrReporter.reportPendingAnrs(any()) }
    }

    @Test
    fun `main process runs the ANR drain`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

        CrashReportingBootstrap.onCreate(app, scope, anrReporter, isSenderProcess = { false })
        advanceUntilIdle()

        coVerify(exactly = 1) { anrReporter.reportPendingAnrs(any()) }
    }
}
