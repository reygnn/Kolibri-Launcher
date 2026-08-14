package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
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
 *  - [attachBaseContext]: init ACRA, gate consent (X2), install the uncaught
 *    handler — the hard sequence invariants of §12 live here.
 *  - [onCreate]: plant the single delivery tree, drain post-mortem ANRs, start
 *    the recovery watchdog.
 *
 * Plain `Timber.e` (not `silentError`) throughout, per Rule 9: this is
 * crash-handling infrastructure on the bootstrap path — a DEBUG throw here would
 * recurse into the very path it is the safety net for.
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
     * Called from `attachBaseContext` with the base context. Enforces the §12
     * ordering:
     *  1. `init` → `setEnabled(false)` with no intervening statement (A1) —
     *     ACRA is constructed disabled, only a verified `Granted` enables it.
     *  2. `ACRA.init` → install [UncaughtCrashHandler] AFTER, so its delegate is
     *     ACRA's reporter (installed during init), not the pre-ACRA handler.
     *
     * The consent read + toggle are process-gated (X2): only the main process
     * reads; in the `:acra` sender process `errorReporter` is a stub and the
     * read would be a wasted cross-process DataStore hit.
     */
    fun attachBaseContext(app: Application, base: Context) {
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

        // ⚠️ KNOWN BUG (confirmed on-device 2026-08-14, release 0.99.167, stack
        // deobfuscated via mapping.txt) — the bootstrap consent read below throws
        // NullPointerException on EVERY cold start. `base.consentDataStore` resolves
        // the androidx `PreferenceDataStoreSingletonDelegate.getValue`, which
        // dereferences `base.applicationContext` — NULL during attachBaseContext
        // (the framework assigns LoadedApk.mApplication only AFTER newApplication()
        // returns, i.e. after this method). The NPE aborts applyConsentGate.
        //
        // What that BREAKS (bootstrap-time reporting):
        //   1. The Thread.setDefaultUncaughtExceptionHandler(UncaughtCrashHandler)
        //      call BELOW is skipped (the throw escapes to KolibriLauncherApp's
        //      outer catch) → the unified breadcrumb handler is never installed.
        //   2. ACRA stays disabled through onCreate, so `reportPendingAnrsAsync`
        //      drains post-mortem ANRs into a DISABLED reporter → ANR reports lost.
        //   3. Crashes in the early cold-start window go unreported.
        //
        // What SURVIVES (do NOT overstate this as "ACRA is dead"): for a Granted
        // user, `MainActivity.checkAndShowCrashReportConsent` → `reaffirmConsent`
        // re-enables ACRA via the POST-Hilt repository (valid @ApplicationContext,
        // works). That reaffirm was added as a net for EXACTLY this failed-bootstrap
        // case (see its comment). So steady-state runtime crash/silentError
        // reporting recovers once MainActivity starts; only the three bootstrap-time
        // paths above are defeated.
        //
        // Invisible because: unit tests exercise readDecision(dataStore) with a FAKE
        // store, never this context→extension path; Rule 7 swallows the throw
        // ("CRITICAL: Failed to initialize crash reporting"); "ACRA off" is the
        // privacy-safe default; and the MainActivity net masks the steady state.
        // Present since the Cutover-B rewrite (83dbff00). FIX: gate consent where
        // applicationContext exists + a regression test over the REAL extension
        // path. See the fix/acra-consent-bootstrap-npe branch.
        // §12·1: immediately after init, disable first, then read consent (A1).
        applyConsentGate(
            setEnabled = { ACRA.errorReporter.setEnabled(it) },
            // X2: only the main process reads consent and toggles; the `:acra`
            // sender process (errorReporter is a stub there) stays disabled.
            readDecision = {
                if (ACRA.isACRASenderServiceProcess()) null
                // Traced (cold-start): synchronous DataStore consent read on
                // the Main thread — prime suspect for cold-start latency.
                else LaunchTrace.section(LaunchTrace.Names.COLD_START_CONSENT_READ) {
                    runBlocking { ConsentBootstrap.readDecision(base) }
                }
            },
        )

        // §12·2: install AFTER init so the delegate is ACRA's ErrorReporterImpl.
        // Unified across RELEASE and DEBUG (G1).
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
    ) {
        // X2: the `:acra` sender process must run NONE of this — symmetric with
        // attachBaseContext, which gates its consent read the same way. Both the
        // ANR drain and the watchdog write process-shared state the main process
        // owns: the settings-DataStore watermark (AnrReporter) and the LoopGuard
        // kill-counter file under noBackupFilesDir. A second writer in `:acra`
        // is a cross-process race — a fresh ANR's watermark advanced past without
        // a live reporter (silent loss), plus a clobbered kill count. AcraTree
        // would only forward to a stub errorReporter here anyway.
        if (isSenderProcess()) return

        plantDeliveryTree()
        reportPendingAnrsAsync(scope, anrReporter)
        startWatchdog()
    }

    private fun reportPendingAnrsAsync(scope: CoroutineScope, anrReporter: AnrReporter) {
        scope.launch {
            try {
                anrReporter.reportPendingAnrs { report ->
                    val description = report.description.ifBlank { "ANR" }
                    // Single delivery path (Timber.e → AcraTree → handleSilentException).
                    // No client throttle to bypass (B3). An extra handleException
                    // here would double-send — don't add one. Rule 9: plain Timber.e.
                    Timber.e(
                        AnrException("$description\n\n${report.threadDump.orEmpty()}"),
                        "ANR (post-mortem from ApplicationExitInfo)",
                    )
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error walking pending ANRs")
            }
        }
    }

    private fun startWatchdogAfterBootstrap(app: Application) {
        Handler(Looper.getMainLooper()).post {
            try {
                RecoveryWatchdog(
                    loopGuard = LoopGuard(File(app.noBackupFilesDir, WATCHDOG_KILL_STORE)),
                    capture = { stall -> Timber.e(stall, "Main-looper stall (watchdog capture)") },
                ).start()
            } catch (e: Throwable) {
                Timber.e(e, "Failed to start RecoveryWatchdog")
            }
        }
    }
}
