package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AcraTree
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrException
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrReporter
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

            reportContent = listOf(
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.STACK_TRACE,
            )
        }

        // §12·1: immediately after init, no intervening statement (A1).
        ACRA.errorReporter.setEnabled(false)

        // X2: only the main process reads consent and toggles.
        if (!ACRA.isACRASenderServiceProcess()) {
            val granted = runBlocking { ConsentBootstrap.readDecision(base) == ConsentDecision.Granted }
            if (granted) {
                ACRA.errorReporter.setEnabled(true)
                Timber.i("ACRA enabled from stored consent")
            }
        }

        // §12·2: install AFTER init so the delegate is ACRA's ErrorReporterImpl.
        // Unified across RELEASE and DEBUG (G1).
        Thread.setDefaultUncaughtExceptionHandler(
            UncaughtCrashHandler(Thread.getDefaultUncaughtExceptionHandler()),
        )
    }

    /**
     * Called from `onCreate`. Plants the single delivery tree, drains
     * post-mortem ANRs on [scope], and starts the watchdog *after* onCreate
     * returns (§12·3) so heavy cold-start work does not self-trip a
     * kill-restart loop.
     */
    fun onCreate(app: Application, scope: CoroutineScope, anrReporter: AnrReporter) {
        Timber.plant(AcraTree())
        reportPendingAnrsAsync(scope, anrReporter)
        startWatchdogAfterBootstrap(app)
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
