package com.github.reygnn.kolibri_launcher.ui.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.reygnn.kolibri_launcher.core.IoDispatcher
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-mortem ANR reporter built on `ApplicationExitInfo` (API 30+,
 * we ship minSdk 36 so the gate is implicit).
 *
 * Replaces the previous `com.github.anrwatchdog:anrwatchdog:1.4.0`
 * dependency (unmaintained since 2018) with ~80 lines of AOSP API.
 *
 * ## Behaviour vs. ANRWatchDog (the trade-off)
 *
 * |                       | ANRWatchDog                              | AnrReporter                                       |
 * |-----------------------|------------------------------------------|---------------------------------------------------|
 * | Detection moment      | Live, after 5 s main-thread hang         | Post-mortem, on the next app start                |
 * | Captures              | Soft ANRs (process still alive)          | Hard ANRs only (system actually killed the proc)  |
 * | Thread dump           | Self-captured, main thread only          | System-supplied (all threads + locks via AEI)     |
 * | Background overhead   | One sampling thread                      | Zero — query runs once on next launch             |
 * | External dependency   | Yes (1.4.0, unmaintained)                | No                                                 |
 *
 * **Soft-ANR loss is intentional.** A user who taps "Wait" on the system
 * ANR dialog won't show up in our reports anymore. In exchange the reports
 * we *do* get carry the system-supplied multi-thread dump (lock state and
 * all), which beats the watchdog's main-thread-only sample for diagnosis.
 *
 * ## Future-self note: when to add a recovery watchdog
 *
 * If the AEI-based reports look thin (way fewer ANRs than user complaints
 * suggest, or important repro paths missing) after 1–2 releases, the
 * fallback is a small main-thread sampling watchdog *on top of* this
 * reporter — not a return to ANRWatchDog. Don't pre-build it: YAGNI.
 *
 * ## Storage
 *
 * Watermark timestamp lives in the project's [settingsDataStore] (the
 * single Preferences DataStore, per CLAUDE.md Rule 5). Key:
 * `anr_reporter_last_reported_ts`. The read+write are both `suspend`,
 * which fits cleanly because [reportPendingAnrs] is itself called from
 * an application-scope coroutine in [com.github.reygnn.kolibri_launcher.KolibriLauncherApp.onCreate].
 *
 * ## Dedup contract
 *
 * `reportPendingAnrs` walks the historical exit reasons in chronological
 * order. After the [handler] returns for a given report, the watermark is
 * advanced to that report's timestamp. If the handler *throws*, the loop
 * stops, the watermark stays put, and the un-reported ANRs are retried on
 * the next launch.
 *
 * **Best-effort delivery — the retry path does not cover ACRA failures.**
 * The retry-on-throw mechanism only fires when the handler actually throws.
 * The production handler (`Timber.e` -> `AcraTree.reportErrorToAcra` in
 * [com.github.reygnn.kolibri_launcher.KolibriLauncherApp]) deliberately
 * *swallows* any `handleSilentException` failure — crash reporting must
 * never itself crash the app (CLAUDE.md Rule 7/9). So a failed ACRA send
 * never reaches the handler boundary: the watermark advances anyway and
 * that post-mortem ANR is dropped. This is an accepted trade-off — if ACRA
 * is broken there are no reports to lose anyway. Do **not** make the
 * handler rethrow to "recover" the ANR: that would defeat the crash-infra
 * swallow it sits behind. The only path that currently triggers a retry is
 * an unswallowed `Throwable` from the handler body itself (e.g. OOM while
 * building the synthetic report), not an ACRA delivery failure.
 */
@Singleton
class AnrReporter @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val dataStore: DataStore<Preferences>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Walks all ANRs in `getHistoricalProcessExitReasons` newer than the
     * stored watermark, calls [handler] for each in chronological order,
     * and advances the watermark after each successful invocation.
     *
     * Errors in [handler]: the loop stops. Watermark for the failing entry
     * is *not* advanced. Caller decides whether to log/swallow inside
     * [handler] (then the loop continues) or to let it propagate (then
     * the loop aborts and unprocessed ANRs are retried next launch).
     */
    suspend fun reportPendingAnrs(handler: suspend (AnrReport) -> Unit) {
        val pending = newAnrsSinceLastReport()
        for (report in pending) {
            handler(report)
            markReported(report)
        }
    }

    /**
     * Lower-level API: returns ANRs newer than the watermark without
     * calling any handler. Exposed for tests; production should prefer
     * [reportPendingAnrs].
     */
    internal suspend fun newAnrsSinceLastReport(): List<AnrReport> = withContext(ioDispatcher) {
        val am = appContext.getSystemService(ActivityManager::class.java)
            ?: return@withContext emptyList()

        val lastReported = readWatermark()

        try {
            am.getHistoricalProcessExitReasons(appContext.packageName, /*pid=*/0, /*maxNum=*/0)
                .asSequence()
                .filter { it.reason == ApplicationExitInfo.REASON_ANR }
                .filter { it.timestamp > lastReported }
                .sortedBy { it.timestamp }
                .map(::toReport)
                .toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading historical exit reasons")
            emptyList()
        }
    }

    /**
     * Lower-level API: advances the watermark past [report]. Internal
     * because [reportPendingAnrs] is the supported entry point.
     */
    internal suspend fun markReported(report: AnrReport) = withContext(ioDispatcher) {
        try {
            dataStore.edit { prefs ->
                val current = prefs[KEY_WATERMARK] ?: 0L
                if (report.timestamp > current) {
                    prefs[KEY_WATERMARK] = report.timestamp
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error advancing ANR watermark")
        }
    }

    private suspend fun readWatermark(): Long {
        return try {
            dataStore.data.first()[KEY_WATERMARK] ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading ANR watermark")
            0L
        }
    }

    private fun toReport(info: ApplicationExitInfo): AnrReport {
        // System trace stream may legitimately be null (rare device builds
        // without the bug-report process), or read may throw IOException
        // mid-read on a stream-level corruption. Both are recoverable by
        // returning a report without the dump — better than dropping the
        // ANR entirely just because the trace blob couldn't be slurped.
        val trace = try {
            info.traceInputStream?.bufferedReader()?.use { it.readText() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading ANR trace stream")
            null
        }
        return AnrReport(
            timestamp = info.timestamp,
            description = info.description.orEmpty(),
            importance = info.importance,
            threadDump = trace,
        )
    }

    private companion object {
        val KEY_WATERMARK = longPreferencesKey("anr_reporter_last_reported_ts")
    }
}

/**
 * Single ANR record extracted from [ApplicationExitInfo]. Pure-data, no
 * Android types — fine to log, serialise, attach to ACRA reports etc.
 *
 * @property timestamp Wallclock millis when the system registered the ANR.
 * @property description System-supplied short description, e.g.
 *     "Input dispatching timed out (Waiting because no window has focus…)".
 * @property importance Process importance at ANR time (one of the
 *     `RunningAppProcessInfo.IMPORTANCE_*` constants).
 * @property threadDump Full multi-thread dump including held locks, or
 *     null if the system did not supply one (or reading it failed).
 */
data class AnrReport(
    val timestamp: Long,
    val description: String,
    val importance: Int,
    val threadDump: String?,
)
