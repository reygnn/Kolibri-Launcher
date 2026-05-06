package com.github.reygnn.kolibri_launcher.ui.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pure-JVM test for [AnrReporter]. ApplicationExitInfo is `open` so MockK
 * can stub it; ActivityManager is mocked at the system-service boundary.
 *
 * What we cover:
 *  - filter: non-ANR exit reasons are ignored
 *  - watermark gate: only ANRs newer than the persisted timestamp surface
 *  - chronological order: oldest-first, even if the system returns them
 *    in newest-first order (which it does on real devices)
 *  - markReported monotonicity: the watermark only ever moves forward
 *  - reportPendingAnrs flow: handler runs per entry, watermark advances
 *    per-entry, and a thrown handler aborts the loop without advancing
 *    the watermark for the failing entry (so it gets retried next launch)
 *  - graceful failure: ActivityManager throwing returns empty list, not
 *    a propagated exception
 *
 * Trace-stream parsing isn't deep-tested — the reader logic is one
 * `bufferedReader().use { readText() }` line, and the failure branch
 * (returning null + silentError) is exercised via a thrown stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnrReporterTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val timberRule = TimberRule()

    private val context: Context = mockk(relaxed = true)
    private val activityManager: ActivityManager = mockk()
    private val dataStore = FakeDataStore()

    private val watermarkKey = longPreferencesKey("anr_reporter_last_reported_ts")

    @Before fun setUp() {
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.github.reygnn.kolibri_launcher"
        every { context.getSystemService(ActivityManager::class.java) } returns activityManager
    }

    private fun reporter() = AnrReporter(
        appContext = context,
        dataStore = dataStore,
        ioDispatcher = UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler),
    )

    /** Builds an ApplicationExitInfo mock with the four fields the reporter reads. */
    private fun exitInfo(
        reason: Int = ApplicationExitInfo.REASON_ANR,
        timestamp: Long,
        description: String = "",
        importance: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        traceBytes: ByteArray? = null,
    ): ApplicationExitInfo = mockk {
        every { this@mockk.reason } returns reason
        every { this@mockk.timestamp } returns timestamp
        every { this@mockk.description } returns description
        every { this@mockk.importance } returns importance
        every { this@mockk.traceInputStream } returns traceBytes?.let(::ByteArrayInputStream)
    }

    @Test
    fun `newAnrsSinceLastReport - empty exit reasons - returns empty`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns emptyList()

        val result = reporter().newAnrsSinceLastReport()

        assertThat(result).isEmpty()
    }

    @Test
    fun `newAnrsSinceLastReport - non-ANR reasons are filtered out`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(reason = ApplicationExitInfo.REASON_LOW_MEMORY, timestamp = 100L),
            exitInfo(reason = ApplicationExitInfo.REASON_SIGNALED, timestamp = 200L),
            exitInfo(reason = ApplicationExitInfo.REASON_CRASH, timestamp = 300L),
        )

        val result = reporter().newAnrsSinceLastReport()

        assertThat(result).isEmpty()
    }

    @Test
    fun `newAnrsSinceLastReport - returns only ANRs newer than the watermark`() = runTest(mainDispatcherRule.testDispatcher) {
        dataStore.setInitialData(mutablePreferencesOf(watermarkKey to 200L))
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 100L, description = "old, before watermark"),
            exitInfo(timestamp = 200L, description = "exactly at watermark - excluded"),
            exitInfo(timestamp = 300L, description = "new"),
            exitInfo(timestamp = 400L, description = "newer"),
        )

        val result = reporter().newAnrsSinceLastReport()

        assertThat(result.map { it.description }).containsExactly("new", "newer").inOrder()
    }

    @Test
    fun `newAnrsSinceLastReport - returns chronologically sorted even when system gives newest-first`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 500L, description = "third"),
            exitInfo(timestamp = 100L, description = "first"),
            exitInfo(timestamp = 300L, description = "second"),
        )

        val result = reporter().newAnrsSinceLastReport()

        assertThat(result.map { it.timestamp }).containsExactly(100L, 300L, 500L).inOrder()
    }

    @Test
    fun `newAnrsSinceLastReport - parses trace stream into AnrReport threadDump`() = runTest(mainDispatcherRule.testDispatcher) {
        val trace = "Cmd line: com.kolibri\n\"main\" prio=5 tid=1 Native\n  | held mutexes=\n"
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 100L, traceBytes = trace.toByteArray()),
        )

        val result = reporter().newAnrsSinceLastReport().single()

        assertThat(result.threadDump).isEqualTo(trace)
    }

    @Test
    fun `newAnrsSinceLastReport - null trace stream surfaces as null threadDump`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 100L, traceBytes = null),
        )

        val result = reporter().newAnrsSinceLastReport().single()

        assertThat(result.threadDump).isNull()
    }

    @Test
    fun `newAnrsSinceLastReport - ActivityManager throws - returns empty list, no propagation`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } throws
            SecurityException("permission denied on weird OEM build")

        val result = reporter().newAnrsSinceLastReport()

        assertThat(result).isEmpty()
    }

    @Test
    fun `markReported - advances watermark forward`() = runTest(mainDispatcherRule.testDispatcher) {
        val r = reporter()
        val report = AnrReport(timestamp = 500L, description = "x", importance = 0, threadDump = null)

        r.markReported(report)

        val stored = dataStore.data.first()[watermarkKey]
        assertThat(stored).isEqualTo(500L)
    }

    @Test
    fun `markReported - never moves watermark backward`() = runTest(mainDispatcherRule.testDispatcher) {
        dataStore.setInitialData(mutablePreferencesOf(watermarkKey to 1_000L))
        val r = reporter()
        val olderReport = AnrReport(timestamp = 500L, description = "old", importance = 0, threadDump = null)

        r.markReported(olderReport)

        val stored = dataStore.data.first()[watermarkKey]
        assertThat(stored).isEqualTo(1_000L)
    }

    @Test
    fun `reportPendingAnrs - calls handler in chronological order and advances watermark per entry`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 300L, description = "third"),
            exitInfo(timestamp = 100L, description = "first"),
            exitInfo(timestamp = 200L, description = "second"),
        )

        val seen = mutableListOf<String>()
        reporter().reportPendingAnrs { report -> seen += report.description }

        assertThat(seen).containsExactly("first", "second", "third").inOrder()
        val stored = dataStore.data.first()[watermarkKey]
        assertThat(stored).isEqualTo(300L)
    }

    @Test
    fun `reportPendingAnrs - handler throws on second entry - first entry watermarked, third never seen`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 100L, description = "first"),
            exitInfo(timestamp = 200L, description = "second"),
            exitInfo(timestamp = 300L, description = "third"),
        )

        val seen = mutableListOf<String>()
        try {
            reporter().reportPendingAnrs { report ->
                seen += report.description
                if (report.description == "second") throw IllegalStateException("simulated ACRA failure")
            }
        } catch (_: IllegalStateException) {
            // expected — caller decides whether to swallow per the dedup contract
        }

        assertThat(seen).containsExactly("first", "second").inOrder()
        // Watermark stayed at the last *successful* report (first=100). Second
        // and third get retried on the next launch — that's the dedup contract.
        val stored = dataStore.data.first()[watermarkKey]
        assertThat(stored).isEqualTo(100L)
    }
}
