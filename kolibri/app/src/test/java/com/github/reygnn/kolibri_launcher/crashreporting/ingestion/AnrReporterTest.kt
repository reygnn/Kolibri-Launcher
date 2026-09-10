package com.github.reygnn.kolibri_launcher.crashreporting.ingestion

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertFailsWith

/**
 * Pure-JVM test for [AnrReporter]. ApplicationExitInfo is `open` so MockK can
 * stub it; ActivityManager is mocked at the system-service boundary.
 *
 * Covers the filter (non-ANR ignored), the watermark gate (only newer ANRs),
 * chronological order, markReported monotonicity, the reportPendingAnrs flow
 * (per-entry watermark advance; a thrown handler aborts without advancing), and
 * graceful failure (ActivityManager throwing returns empty).
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

        assertThat(reporter().newAnrsSinceLastReport()).isEmpty()
    }

    @Test
    fun `newAnrsSinceLastReport - non-ANR reasons are filtered out`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(reason = ApplicationExitInfo.REASON_LOW_MEMORY, timestamp = 100L),
            exitInfo(reason = ApplicationExitInfo.REASON_SIGNALED, timestamp = 200L),
            exitInfo(reason = ApplicationExitInfo.REASON_CRASH, timestamp = 300L),
        )

        assertThat(reporter().newAnrsSinceLastReport()).isEmpty()
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

        assertThat(reporter().newAnrsSinceLastReport().map { it.description })
            .containsExactly("new", "newer").inOrder()
    }

    @Test
    fun `newAnrsSinceLastReport - returns chronologically sorted even when system gives newest-first`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 500L, description = "third"),
            exitInfo(timestamp = 100L, description = "first"),
            exitInfo(timestamp = 300L, description = "second"),
        )

        assertThat(reporter().newAnrsSinceLastReport().map { it.timestamp })
            .containsExactly(100L, 300L, 500L).inOrder()
    }

    @Test
    fun `newAnrsSinceLastReport - parses trace stream into AnrReport threadDump`() = runTest(mainDispatcherRule.testDispatcher) {
        val trace = "Cmd line: com.kolibri\n\"main\" prio=5 tid=1 Native\n  | held mutexes=\n"
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 100L, traceBytes = trace.toByteArray()),
        )

        assertThat(reporter().newAnrsSinceLastReport().single().threadDump).isEqualTo(trace)
    }

    @Test
    fun `newAnrsSinceLastReport - null trace stream surfaces as null threadDump`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns listOf(
            exitInfo(timestamp = 100L, traceBytes = null),
        )

        assertThat(reporter().newAnrsSinceLastReport().single().threadDump).isNull()
    }

    @Test
    fun `newAnrsSinceLastReport - ActivityManager throws - returns empty list, no propagation`() = runTest(mainDispatcherRule.testDispatcher) {
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } throws
            SecurityException("permission denied on weird OEM build")

        assertThat(reporter().newAnrsSinceLastReport()).isEmpty()
    }

    @Test
    fun `markReported - advances watermark forward`() = runTest(mainDispatcherRule.testDispatcher) {
        reporter().markReported(AnrReport(timestamp = 500L, description = "x", importance = 0, threadDump = null))

        assertThat(dataStore.data.first()[watermarkKey]).isEqualTo(500L)
    }

    @Test
    fun `markReported - never moves watermark backward`() = runTest(mainDispatcherRule.testDispatcher) {
        dataStore.setInitialData(mutablePreferencesOf(watermarkKey to 1_000L))

        reporter().markReported(AnrReport(timestamp = 500L, description = "old", importance = 0, threadDump = null))

        assertThat(dataStore.data.first()[watermarkKey]).isEqualTo(1_000L)
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
        assertThat(dataStore.data.first()[watermarkKey]).isEqualTo(300L)
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
        // Watermark stayed at the last *successful* report (first=100); second
        // and third get retried next launch — the dedup contract.
        assertThat(dataStore.data.first()[watermarkKey]).isEqualTo(100L)
    }

    // ---------- failure branches (AN3 + cancellation) ----------

    @Test
    fun `markReported - when the write fails - swallows and does not advance the watermark`() = runTest(mainDispatcherRule.testDispatcher) {
        dataStore.setInitialData(mutablePreferencesOf(watermarkKey to 100L))
        dataStore.makeEditFail()

        // AN3: a failed watermark write is swallowed (silentError), not thrown,
        // and the watermark is NOT advanced — the ANR is retried next launch.
        reporter().markReported(AnrReport(timestamp = 500L, description = "x", importance = 0, threadDump = null))

        dataStore.resetErrorFlags()
        assertThat(dataStore.data.first()[watermarkKey]).isEqualTo(100L)
    }

    @Test
    fun `markReported - when the write is cancelled - propagates CancellationException`() = runTest(mainDispatcherRule.testDispatcher) {
        dataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            reporter().markReported(AnrReport(timestamp = 500L, description = "x", importance = 0, threadDump = null))
        }
    }

    @Test
    fun `newAnrsSinceLastReport - when the watermark read is cancelled - propagates CancellationException`() = runTest(mainDispatcherRule.testDispatcher) {
        // FakeDataStore only cancels its write path, so inject a cancelling read
        // via a mock — guards that the CancellationException catch is ordered
        // BEFORE the generic Exception->emptyList fallback in readWatermark.
        val cancellingStore = mockk<DataStore<Preferences>>()
        every { cancellingStore.data } returns flow {
            throw CancellationException("simulated read cancellation")
        }
        val r = AnrReporter(
            appContext = context,
            dataStore = cancellingStore,
            ioDispatcher = UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler),
        )

        assertFailsWith<CancellationException> {
            r.newAnrsSinceLastReport()
        }
    }
}
