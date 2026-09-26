package com.github.reygnn.nyx_launcher

import com.github.reygnn.launcher.core.KolibriLog
import com.github.reygnn.launcher.core.SyncInstalledAppsToHolder
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the drive contract of [InstalledAppsHolderPump]: the single long-lived
 * collection of [SyncInstalledAppsToHolder.outcomes], its one report site
 * ([SyncInstalledAppsToHolder.Outcome.FailedNoCache] → [TimberWrapper.reportToAcra]),
 * the `RESTART_DELAY_MS`-backed [kotlinx.coroutines.flow.retryWhen]
 * re-subscribe on a freak upstream error, cancellation propagation (no retry), and the
 * idempotency guard on [InstalledAppsHolderPump.start].
 *
 * JVM-only (no Android runtime in the pump). Dispatcher: the project convention — ONE
 * source, [MainDispatcherRule]'s [kotlinx.coroutines.test.StandardTestDispatcher], handed
 * both to the SUT (as its `dispatcher`, so the pump's own scope shares the scheduler) and
 * to `runTest(...)`, so virtual time is unified (TESTING_CONVENTIONS.kt).
 *
 * `reportToAcra` is observed by installing a [KolibriLog.taggedErrorHandler] and matching
 * on the [TimberWrapper.ACRA_REPORT_TAG] intent tag — the same seam `AcraTree` gates on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledAppsHolderPumpTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sync = mockk<SyncInstalledAppsToHolder>()

    // Captured tagged-error routes: (tag, throwable, message). reportToAcra and
    // silentError both land here via KolibriLog; we filter by intent tag.
    private data class LoggedError(val tag: String, val throwable: Throwable?, val message: String)

    private val loggedErrors = mutableListOf<LoggedError>()

    // The production backoff (now internal), referenced directly so there's no hand-copied value.
    private val restartDelayMs = InstalledAppsHolderPump.RESTART_DELAY_MS

    @Before
    fun installLogHandlerAndPreventCrash() {
        // Observe the report/log seam that TimberWrapper routes through.
        KolibriLog.taggedErrorHandler = { tag, throwable, message ->
            loggedErrors += LoggedError(tag, throwable, message)
        }
        // The retryWhen path calls TimberWrapper.silentError, which crashes-in-DEBUG.
        // Neutralize it so the restart test asserts the recovery path, not a JVM death
        // (guards against any test flipping the shared TimberWrapper.isDebugBuild flag).
        TimberWrapper.preventCrashForTesting.set(true)
    }

    @After
    fun restoreLogHandlerAndCrash() {
        KolibriLog.taggedErrorHandler = { _, _, _ -> }
        TimberWrapper.preventCrashForTesting.set(false)
    }

    @Test
    fun failed_no_cache_outcome_is_reported_to_acra() = runTest(mainDispatcherRule.dispatcher) {
        val cause = IllegalStateException("cold-start load failed, no cache")
        every { sync.outcomes() } returns flow {
            emit(SyncInstalledAppsToHolder.Outcome.FailedNoCache(cause))
        }

        InstalledAppsHolderPump(sync, mainDispatcherRule.dispatcher).start()
        advanceUntilIdle()

        val acraReports = loggedErrors.filter { it.tag == TimberWrapper.ACRA_REPORT_TAG }
        assertThat(acraReports).hasSize(1)
        assertThat(acraReports.single().throwable).isSameInstanceAs(cause)
    }

    @Test
    fun quiet_outcomes_are_not_reported_to_acra() = runTest(mainDispatcherRule.dispatcher) {
        // Loaded / EmptyLoaded / FailedKeptLastGood need no reaction — draining them must
        // not touch the single report site (guards against the report becoming tautological).
        every { sync.outcomes() } returns flow {
            emit(SyncInstalledAppsToHolder.Outcome.Loaded)
            emit(SyncInstalledAppsToHolder.Outcome.EmptyLoaded)
            emit(SyncInstalledAppsToHolder.Outcome.FailedKeptLastGood)
        }

        InstalledAppsHolderPump(sync, mainDispatcherRule.dispatcher).start()
        advanceUntilIdle()

        assertThat(loggedErrors.filter { it.tag == TimberWrapper.ACRA_REPORT_TAG }).isEmpty()
    }

    @Test
    fun a_freak_upstream_error_re_subscribes_after_the_restart_backoff() =
        runTest(mainDispatcherRule.dispatcher) {
            // First collection throws a non-cancellation error; the second drains. retryWhen
            // must log via silentError and re-subscribe only AFTER RESTART_DELAY_MS.
            val collectCount = AtomicInteger(0)
            every { sync.outcomes() } returns countingFlow(collectCount) { n ->
                if (n == 1) throw RuntimeException("freak upstream boom")
                emit(SyncInstalledAppsToHolder.Outcome.Loaded) // second subscription drains
            }

            InstalledAppsHolderPump(sync, mainDispatcherRule.dispatcher).start()
            runCurrent() // first collection runs, throws, retryWhen schedules the backoff delay
            assertThat(collectCount.get()).isEqualTo(1) // only the first subscription so far

            advanceTimeBy(restartDelayMs - 1)
            runCurrent()
            assertThat(collectCount.get()).isEqualTo(1) // backoff not yet elapsed: no re-subscribe

            advanceTimeBy(2) // let the restart backoff elapse
            advanceUntilIdle()
            assertThat(collectCount.get()).isEqualTo(2) // re-subscribed and drained the retry

            // The restart was logged via silentError (SILENT_ERROR tag), not reported to ACRA.
            assertThat(loggedErrors.map { it.tag }).contains(TimberWrapper.SILENT_LOG_TAG)
            assertThat(loggedErrors.filter { it.tag == TimberWrapper.ACRA_REPORT_TAG }).isEmpty()
        }

    @Test
    fun a_cancellation_from_outcomes_propagates_and_is_not_retried() =
        runTest(mainDispatcherRule.dispatcher) {
            val collectCount = AtomicInteger(0)
            every { sync.outcomes() } returns countingFlow(collectCount) {
                throw CancellationException("upstream cancelled")
            }

            InstalledAppsHolderPump(sync, mainDispatcherRule.dispatcher).start()
            advanceUntilIdle()

            // Cancellation ends the collecting coroutine; retryWhen returns false for it, so the
            // flow is collected exactly once — never re-subscribed.
            assertThat(collectCount.get()).isEqualTo(1)
            assertThat(loggedErrors.filter { it.tag == TimberWrapper.ACRA_REPORT_TAG }).isEmpty()
        }

    @Test
    fun start_is_idempotent_a_second_call_launches_no_second_collection() =
        runTest(mainDispatcherRule.dispatcher) {
            val collectCount = AtomicInteger(0)
            every { sync.outcomes() } returns countingFlow(collectCount) {
                emit(SyncInstalledAppsToHolder.Outcome.Loaded)
            }

            val pump = InstalledAppsHolderPump(sync, mainDispatcherRule.dispatcher)
            pump.start()
            pump.start() // second call must be a no-op (single-writer invariant)
            advanceUntilIdle()

            assertThat(collectCount.get()).isEqualTo(1) // ONE collection, not two
            verify(exactly = 1) { sync.outcomes() }
        }

    /**
     * A cold flow that records each subscription in [counter] (incremented as its first act,
     * so the count is visible even when [body] throws), then runs [body] with the 1-based
     * subscription number.
     */
    private fun countingFlow(
        counter: AtomicInteger,
        body: suspend kotlinx.coroutines.flow.FlowCollector<SyncInstalledAppsToHolder.Outcome>.(subscription: Int) -> Unit,
    ): Flow<SyncInstalledAppsToHolder.Outcome> = flow {
        val n = counter.incrementAndGet()
        body(n)
    }
}
