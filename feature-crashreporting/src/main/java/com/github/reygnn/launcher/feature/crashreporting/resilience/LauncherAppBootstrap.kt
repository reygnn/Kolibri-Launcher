package com.github.reygnn.launcher.feature.crashreporting.resilience

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.feature.crashreporting.ToastErrorTree
import com.github.reygnn.launcher.feature.crashreporting.ingestion.AnrDrainer
import com.github.reygnn.launcher.feature.crashreporting.wireKolibriLogToTimber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * The shared part of both apps' `Application` (SPEC_NYX_REWRITE 1c-2, D2): crash
 * reporting, logging and the Rule-7 safety layers, written once so the two apps can
 * no longer differ in how carefully they start. Each app keeps its own
 * `Application` class for what is genuinely its own (receivers, caches, signals).
 *
 * Rule 7 (the Application is multi-layer crash-safe) and Rule 9 (this is crash
 * infrastructure): every block is guarded by a `catch (Throwable)`. Before Timber is
 * wired the fallback is `android.util.Log`; after that it is
 * [TimberWrapper.reportToAcra] — never `silentError`, which throws in DEBUG and would
 * recurse into the very path it is the safety net for.
 */
object LauncherAppBootstrap {

    /** Strips the anonymous-class suffix ("Foo$1") from the DebugTree tag. DEBUG-only. */
    private val ANON_CLASS_SUFFIX = Regex("\\$\\d+")

    /**
     * Call from `Application.attachBaseContext`, after `super`. Initialises ACRA
     * (disabled until consent) and installs the uncaught handler. Timber is not wired
     * yet, so a failure falls back to `Log.e` — and even that is guarded.
     */
    fun attachBaseContext(app: Application, base: Context?, config: AcraConfig, logTag: String) {
        if (base == null) return
        try {
            CrashReportingBootstrap.attachBaseContext(app, config)
        } catch (e: Throwable) {
            try {
                Log.e(logTag, "CRITICAL: Failed to initialize crash reporting", e)
            } catch (ignored: Throwable) {
                // Even logging can fail — nothing left to do.
            }
        }
    }

    /**
     * Call first in `Application.onCreate`, after `super` (Hilt has injected by then).
     * Wires :core's logging seam, plants the DEBUG trees (tag `<tagPrefix>_<Class>`),
     * turns on StrictMode in DEBUG, then runs the crash bootstrap — consent read,
     * delivery tree, post-mortem ANR drain, watchdog. [traceBootstrap] wraps only the
     * crash-bootstrap call (Kolibri times it with LaunchTrace; default: untraced).
     */
    fun onCreate(
        app: Application,
        isDebug: Boolean,
        logTag: String,
        tagPrefix: String,
        applicationScope: CoroutineScope,
        anrDrainer: AnrDrainer,
        traceBootstrap: (() -> Unit) -> Unit = { it() },
    ) {
        // :core has no Timber on its classpath; it forwards through these lambdas. Must run
        // before any code path that may log.
        wireKolibriLogToTimber(isDebug)
        try {
            if (isDebug) {
                Timber.plant(object : Timber.DebugTree() {
                    override fun createStackElementTag(element: StackTraceElement): String {
                        val className = element.className
                            .substringAfterLast('.')
                            .replace(ANON_CLASS_SUFFIX, "")
                            .replace("$", ".")
                        return "${tagPrefix}_$className"
                    }
                })
                // ERROR logs feed the ErrorEventBus → dev error toast in BaseActivity.
                Timber.plant(ToastErrorTree())
            }
        } catch (e: Throwable) {
            // Timber could not be set up: continue without it.
            Log.e(logTag, "Failed to initialize Timber", e)
        }
        if (isDebug) guard("Error setting up StrictMode") { enableStrictMode() }
        guard("Crash-reporting bootstrap failed") {
            traceBootstrap { CrashReportingBootstrap.onCreate(app, applicationScope, anrDrainer) }
        }
    }

    /** Call from `Application.onTerminate` (emulators only; real devices never call it). */
    fun onTerminate(applicationScope: CoroutineScope) {
        guard("Error in onTerminate") { applicationScope.cancel() }
    }

    /**
     * Runs a lifecycle-callback block of the Application so a throw from it can never
     * crash the process from a system callback (Rule 7); reports via reportToAcra (Rule 9).
     */
    inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            TimberWrapper.reportToAcra(e, what)
        }
    }

    /** DEBUG only: surface main-thread disk/network I/O and leaks in logcat (+ screen flash). */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyFlashScreen()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        Timber.d("StrictMode initialized successfully")
    }
}
