package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.app.Application
import android.os.Handler
import android.util.Log
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.crashreporting.health.CrashReportingHealth
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AcraTree
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrException
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrReporter
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.httpSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.security.TLS
import org.acra.sender.HttpSender
import timber.log.Timber
import java.io.File

/**
 * Owns the ACRA lifecycle wiring, extracted out of `KolibriLauncherApp` so the
 * Application stays thin glue (ACRA_SPEC.md C.5, Rule 10). Two entry points,
 * matching the two Application hooks:
 *
 *  - [attachBaseContext]: init ACRA (constructed disabled, A1) + install the
 *    uncaught handler. No DataStore/consent work here — applicationContext is
 *    null this early, which is exactly what NPE'd the old consent read.
 *  - [onCreate]: gate consent (X2 — enable ACRA only for a verified Granted),
 *    then plant the delivery tree, drain post-mortem ANRs, start the watchdog.
 *    The consent gate runs FIRST so the ANR drain sees an enabled reporter.
 *
 * Report delivery uses [TimberWrapper.reportToAcra] (ACRA_REPORT intent tag, no
 * DEBUG throw), not `silentError`, per Rule 9: this is crash-handling
 * infrastructure on the bootstrap path — a DEBUG throw here would recurse into
 * the very path it is the safety net for. The consent-gate catch keeps
 * `android.util.Log.e` because it may run before AcraTree is planted.
 */
object CrashReportingBootstrap {

    private const val WATCHDOG_KILL_STORE = "crash_watchdog_kills"

    /**
     * The EXACT report content (B5): minimal, no `CUSTOM_DATA`, no Logcat, no
     * device ID. Extracted to a constant so the privacy boundary is pinnable by
     * a test — adding a PII-bearing field is the exact leak B5 forbids. Pinned
     * by `ReportContentTest`.
     */
    @VisibleForTesting
    internal val REPORT_CONTENT = listOf(
        ReportField.PACKAGE_NAME,
        ReportField.ANDROID_VERSION,
        ReportField.APP_VERSION_CODE,
        ReportField.APP_VERSION_NAME,
        ReportField.BRAND,
        ReportField.PHONE_MODEL,
        ReportField.STACK_TRACE,
    )

    /**
     * Called from `attachBaseContext`. Enforces the §12 ordering:
     *  1. `init` → `setEnabled(false)` with no intervening statement (A1) —
     *     ACRA is constructed disabled; only a verified `Granted` enables it,
     *     and that consent read now lives in [onCreate] (see below).
     *  2. `ACRA.init` → install [UncaughtCrashHandler] AFTER, so its delegate is
     *     ACRA's reporter (installed during init), not the pre-ACRA handler.
     *
     * NO consent read here: `context.consentDataStore`'s androidx delegate needs
     * `applicationContext`, which is null during `attachBaseContext` — reading it
     * here NPE'd on every cold start and (because the throw aborted the method)
     * also skipped step 2. Both are why the consent gate moved to [onCreate].
     */
    fun attachBaseContext(app: Application) {
        // Traced (cold-start): ACRA config build + init, reflection-heavy.
        LaunchTrace.section(LaunchTrace.Names.COLD_START_ACRA_INIT) {
            app.initAcra {
                buildConfigClass = BuildConfig::class.java
                reportFormat = StringFormat.JSON

                httpSender {
                    uri = BuildConfig.ACRA_URL
                    basicAuthLogin = BuildConfig.ACRA_LOGIN
                    basicAuthPassword = BuildConfig.ACRA_PASSWORD
                    httpMethod = HttpSender.Method.POST
                    tlsProtocols = listOf(TLS.V1_2, TLS.V1_3)
                }

                reportContent = REPORT_CONTENT
            }
        }

        // A1: disable immediately after init, no intervening statement — ACRA is
        // constructed disabled; only a verified Granted enables it. The consent
        // READ that flips it is in [onCreate], NOT here: it needs the DataStore
        // extension, whose androidx delegate dereferences applicationContext —
        // and that is NULL during attachBaseContext (the framework assigns
        // LoadedApk.mApplication only after newApplication() returns). Reading it
        // here NPE'd on every cold start, silently disabling ACRA and skipping the
        // handler install below (fixed 2026-08-14 by moving the gate to onCreate).
        ACRA.errorReporter.setEnabled(false)

        // §12·2: install AFTER init so the delegate is ACRA's ErrorReporterImpl
        // (unified RELEASE+DEBUG, G1). UNCONDITIONAL and ahead of the consent read
        // (now in onCreate), so a consent-read failure can never skip it — the
        // exact regression that had left the unified handler uninstalled.
        Thread.setDefaultUncaughtExceptionHandler(
            UncaughtCrashHandler(Thread.getDefaultUncaughtExceptionHandler()),
        )
    }

    /**
     * The §12·1 (A1) privacy gate, extracted from [attachBaseContext] so it is
     * JVM-testable without the ACRA singleton (same seam pattern as
     * [AcraTree]/[UncaughtCrashHandler]). Invariants pinned by
     * `CrashReportingBootstrapConsentGateTest`:
     *  - [setEnabled]`(false)` is the FIRST action, before [readDecision] runs —
     *    no window where an active reporter sees an unknown decision (A1);
     *  - ACRA is re-enabled ONLY for a verified [ConsentDecision.Granted] — never
     *    for `NeverAsked`/`Denied` (Rule 8, privacy-by-default);
     *  - a `null` decision (the `:acra` sender process, X2) leaves ACRA disabled.
     */
    @VisibleForTesting
    internal fun applyConsentGate(
        setEnabled: (Boolean) -> Unit,
        readDecision: () -> ConsentDecision?,
    ) {
        setEnabled(false)
        if (readDecision() == ConsentDecision.Granted) {
            setEnabled(true)
            Timber.i("ACRA enabled from stored consent")
        }
    }

    /**
     * Called from `onCreate`. In the main process: plants the single delivery
     * tree, drains post-mortem ANRs on [scope], and starts the watchdog *after*
     * onCreate returns (§12·3) so heavy cold-start work does not self-trip a
     * kill-restart loop. In the `:acra` sender process this is a no-op (X2) —
     * see the gate below for why.
     */
    fun onCreate(
        app: Application,
        scope: CoroutineScope,
        anrReporter: AnrReporter,
        // Seams: default to the real side effects; tests inject the process
        // verdict + recording lambdas so the X2 gate AND each main-process side
        // effect are verifiable without ACRA / a real Timber forest / a real
        // watchdog thread. Same pattern as the injected killSwitch/capture on
        // UncaughtCrashHandler/RecoveryWatchdog.
        isSenderProcess: () -> Boolean = { ACRA.isACRASenderServiceProcess() },
        plantDeliveryTree: () -> Unit = { Timber.plant(AcraTree()) },
        startWatchdog: () -> Unit = { startWatchdogAfterBootstrap(app) },
        // The consent gate moved here from attachBaseContext (2026-08-14): the
        // DataStore extension needs applicationContext, null during attach.
        // onCreate runs after the framework wires the Application, so
        // `app.applicationContext` (used by ConsentBootstrap.readDecision) is valid.
        setEnabled: (Boolean) -> Unit = { ACRA.errorReporter.setEnabled(it) },
        readDecision: () -> ConsentDecision? = {
            // Traced (cold-start): synchronous DataStore consent read on Main.
            LaunchTrace.section(LaunchTrace.Names.COLD_START_CONSENT_READ) {
                runBlocking { ConsentBootstrap.readDecision(app) }
            }
        },
    ) {
        // X2: the `:acra` sender process must run NONE of this — the consent read
        // + toggle, the ANR drain and the watchdog all write process-shared state
        // the main process owns: the settings-DataStore watermark (AnrReporter)
        // and the LoopGuard kill-counter file under noBackupFilesDir. A second
        // writer in `:acra` is a cross-process race — a fresh ANR's watermark
        // advanced past without a live reporter (silent loss), plus a clobbered
        // kill count. AcraTree would only forward to a stub errorReporter anyway.
        if (isSenderProcess()) return

        // A1 gate: disable-then-enable-if-Granted. Runs BEFORE the ANR drain so a
        // Granted user's post-mortem ANRs report into an enabled reporter (ACRA was
        // left disabled at attachBaseContext). MainActivity.reaffirmConsent is now
        // a redundant backup, not the sole carrier.
        //
        // Wrapped so a consent-read failure (the 2026-08 cold-start NPE class) can
        // never crash the app (Rule 7 — this call site is NOT try/catch-wrapped by
        // KolibriLauncherApp.onCreate). On success the health flag flips healthy;
        // on failure it stays UNHEALTHY, which CrashReportingHealthMonitor surfaces
        // out-of-band (notification + Settings hint). Plain android.util.Log, never
        // silentError (Rule 9 — silentError routes through the very ACRA we may
        // have just failed to enable).
        try {
            applyConsentGate(setEnabled, readDecision)
            CrashReportingHealth.markBootstrapGateCompleted()
        } catch (e: Throwable) {
            Log.e("KolibriLauncher", "ACRA consent gate failed in onCreate", e)
        }

        plantDeliveryTree()
        reportPendingAnrsAsync(scope, anrReporter)
        startWatchdog()
    }

    private fun reportPendingAnrsAsync(scope: CoroutineScope, anrReporter: AnrReporter) {
        scope.launch {
            try {
                anrReporter.reportPendingAnrs { report ->
                    val description = report.description.ifBlank { "ANR" }
                    // Single delivery path (reportToAcra → ACRA_REPORT tag →
                    // AcraTree → handleSilentException). No client throttle to
                    // bypass (B3). An extra handleException here would
                    // double-send — don't add one. Rule 9: reportToAcra, no DEBUG throw.
                    TimberWrapper.reportToAcra(
                        AnrException("$description\n\n${report.threadDump.orEmpty()}"),
                        "ANR (post-mortem from ApplicationExitInfo)",
                    )
                }
            } catch (e: Throwable) {
                TimberWrapper.reportToAcra(e, "Error walking pending ANRs")
            }
        }
    }

    private fun startWatchdogAfterBootstrap(app: Application) {
        Handler(Looper.getMainLooper()).post {
            try {
                RecoveryWatchdog(
                    loopGuard = LoopGuard(File(app.noBackupFilesDir, WATCHDOG_KILL_STORE)),
                    capture = { stall ->
                        TimberWrapper.reportToAcra(stall, "Main-looper stall (watchdog capture)")
                    },
                ).start()
            } catch (e: Throwable) {
                TimberWrapper.reportToAcra(e, "Failed to start RecoveryWatchdog")
            }
        }
    }
}
