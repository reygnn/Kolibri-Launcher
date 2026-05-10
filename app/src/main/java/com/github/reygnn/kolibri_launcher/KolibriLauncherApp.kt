/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher

import android.app.Application
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import com.github.reygnn.kolibri_launcher.core.SystemWallpaperColorsSignal
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.data.CrashReportConsentStore
import com.github.reygnn.kolibri_launcher.data.DataMigrationManager
import com.github.reygnn.kolibri_launcher.data.PackageUpdateReceiver
import com.github.reygnn.kolibri_launcher.ui.util.AnrReporter
import com.github.reygnn.kolibri_launcher.ui.util.CrashReportLimiter
import com.github.reygnn.kolibri_launcher.ui.util.RecoveryWatchdog
import com.github.reygnn.kolibri_launcher.ui.util.ToastErrorTree
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * ULTRA CRASH-SAFE Application Class
 *
 * Multi-layer exception handling:
 * - All operations wrapped in try-catch with Throwable
 * - CoroutineExceptionHandler for application scope
 * - Global uncaught exception handler with recovery attempts
 * - ACRA initialized with privacy-by-default
 * - Safe package receiver registration
 * - Protected migration with rollback capability
 * - ACRA spam protection (max 1 report per exception per 24h)
 *
 * This ensures the launcher stays alive even under extreme conditions
 * like OutOfMemoryError, StackOverflowError, or corrupted system state.
 */
@HiltAndroidApp
class KolibriLauncherApp : Application() {

    @Inject
    lateinit var dataMigrationManager: DataMigrationManager
    @Inject
    lateinit var dataStoreBackup: DataStoreBackup
    @Inject
    lateinit var anrReporter: AnrReporter
    @Inject
    lateinit var systemWallpaperColorsSignal: SystemWallpaperColorsSignal

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packageUpdateReceiver = PackageUpdateReceiver()

    // Global exception handler
    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    // Ultra Paranoia: Coroutine exception handler for application scope
    private val applicationExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            Timber.e(throwable, "Uncaught exception in application scope")
        } catch (e: Throwable) {
            // Even logging can fail - silent fallback
            try {
                Log.e("KolibriLauncher", "Exception in app scope and logging failed", throwable)
            } catch (ignored: Throwable) {
                // Absolute last resort - nothing we can do
            }
        }
    }

    /**
     * ACRA must be initialised here — `attachBaseContext` runs before `onCreate`
     * and before any other Application code, so it's the only safe place to install
     * the global crash handler before something else can crash.
     *
     * ## Why `runBlocking` is intentional here
     *
     * `initAcra { ... }` enables crash reporting by default. We disable it
     * immediately afterwards (Rule 8: privacy-by-default) and only re-enable
     * it when the user has given consent. The consent state lives in disk
     * storage, so reading it is unavoidably I/O. We block the main thread
     * for that read deliberately:
     *
     * - Reading async (e.g. by launching a coroutine in `onCreate`) would
     *   open a micro-window between the disable in this method and the
     *   re-enable in `onCreate` during which ACRA stays disabled even when
     *   the user has consented. Small window, but real — and the whole
     *   point of Rule 8 is that the consent decision is the source of truth,
     *   not "consented but the launcher missed it".
     * - The read is small (single key) and effectively free on warm starts.
     * - `runBlocking(Dispatchers.IO)` would not help: the inner
     *   `withContext(Dispatchers.IO)` in [CrashReportConsentStore.hasConsent]
     *   already moves the actual I/O off the calling thread of the suspend
     *   function, but `runBlocking` always blocks its caller. Wrapping it
     *   in another IO dispatcher just adds a hop with no benefit.
     *
     * ## StrictMode noise
     *
     * In DEBUG this triggers a DiskReadViolation. That's expected — it is
     * intentional code, not a bug to fix. Cross-reference `KNOWN_ISSUES.md`
     * (section "Intentional violations") before chasing it. The backing
     * store is the project DataStore (see [CrashReportConsentStore.hasConsent]);
     * the StrictMode noise comes from the synchronous file read, not from
     * the storage choice — `runBlocking { dataStore.data.first() }` blocks
     * the main thread the same way a `SharedPreferences.getBoolean` would.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        if (base != null) {
            try {
                initAcra {
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
                        ReportField.STACK_TRACE
                    )
                }

                // Immediately disable ACRA after initialization (privacy-by-default)
                ACRA.errorReporter.setEnabled(false)

                // Check consent status
                val userHasGivenConsent = runBlocking {
                    CrashReportConsentStore.hasConsent(base)
                }

                // Only enable if user has given consent
                if (userHasGivenConsent) {
                    ACRA.errorReporter.setEnabled(true)
                    Timber.i("ACRA initialized. Enabled: true")
                }
            } catch (e: Throwable) {
                // Ultra paranoid: Catch everything, even in ACRA init
                // Use Android Log as fallback since Timber might not be initialized
                try {
                    Log.e("KolibriLauncher", "CRITICAL: Failed to initialize ACRA", e)
                } catch (ignored: Throwable) {
                    // Even logging can fail - nothing we can do
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Hand BuildConfig.DEBUG to :domain/TimberWrapper (which has no
        // BuildConfig of its own as a pure-Kotlin module). Must run before
        // any code path that may invoke silentError.
        TimberWrapper.isDebugBuild = BuildConfig.DEBUG

        // Wire :domain's KolibriLog to Timber. :domain is a pure-Kotlin
        // module without a Timber dependency on its compile classpath
        // (Timber 5.x is .aar-only); KolibriLog forwards through these
        // lambdas. Must run before any :domain code path can log.
        KolibriLog.dHandler = { message -> Timber.d(message) }
        KolibriLog.wHandler = { throwable, message ->
            if (throwable != null) Timber.w(throwable, message) else Timber.w(message)
        }
        KolibriLog.taggedErrorHandler = { tag, throwable, message ->
            val tree = Timber.tag(tag)
            if (throwable != null) tree.e(throwable, message) else tree.e(message)
        }

        // Initialize ACRA spam protection FIRST (before any crashes can occur)
        try {
            CrashReportLimiter.init(this)
            Timber.d("CrashReportLimiter initialized successfully")
        } catch (e: Throwable) {
            Log.e("KolibriLauncher", "Failed to initialize CrashReportLimiter", e)
        }

        // Setup Timber with crash protection
        try {
            if (BuildConfig.DEBUG) {
                // Custom DebugTree with short tags
                Timber.plant(object : Timber.DebugTree() {
                    override fun createStackElementTag(element: StackTraceElement): String {
                        val className = element.className
                            .substringAfterLast('.')
                            .replace(Regex("\\$\\d+"), "")
                            .replace("$", ".")
                        return "Kolibri_$className"
                    }
                })
                Timber.plant(ToastErrorTree())
            }

            // ACRA tree for both DEBUG and RELEASE
            Timber.plant(AcraTree())
        } catch (e: Throwable) {
            // Timber initialization failed - continue without it
            Log.e("KolibriLauncher", "Failed to initialize Timber", e)
        }

        // Setup global exception handler (optional, ACRA handles it)
        if (BuildConfig.DEBUG) {
            try {
                setupGlobalExceptionHandler()
            } catch (e: Throwable) {
                Timber.e(e, "Failed to setup global exception handler")
            }
        }

        // Setup StrictMode for debugging
        if (BuildConfig.DEBUG) {
            try {
                setupStrictMode()
            } catch (e: Throwable) {
                Timber.e(e, "Error setting up StrictMode")
            }
        }

        reportPendingAnrsAsync()
        startRecoveryWatchdogAfterBootstrap()

        // Receiver registration — der Helper hat seinen eigenen catch(Throwable)
        // mit silentError, also kann hier nichts entkommen. Ein zusätzlicher
        // outer try/catch wäre toter Code.
        Timber.d("[LIFECYCLE] Application.onCreate - Registering receiver...")
        registerPackageUpdateReceiver()

        // Wire SystemWallpaperColorsSignal to WallpaperManager. Drives the
        // AppDrawer's AUTO surface mode (and any future surface that wants
        // to react to system-wallpaper colour-hint changes). Listener +
        // initial poll, both inside the same outer catch — Rule 7 paranoia
        // applies, and Rule 9's plain-Timber.e exception covers this file.
        // No unregister: Application.onTerminate isn't called on real
        // devices, and the signal is a process-lifetime singleton.
        registerSystemWallpaperColorsListener()

        // Data migration - Ultra Paranoid version
        applicationScope.launch(applicationExceptionHandler) {
            try {
                val isFirstLaunch = dataMigrationManager.isFirstLaunch()

                if (isFirstLaunch && BuildConfig.DEBUG) {
                    try {
                        dataStoreBackup.restoreFromBackup()
                    } catch (e: Throwable) {
                        // Bleibt bewusst Timber.e (statt silentError):
                        // dieser Pfad läuft nur in DEBUG, der Catch ist
                        // Fail-Safe-by-Design ("Continue anyway - not
                        // critical"). Ein silentError-Throw würde hier
                        // die anschließende Migration killen UND in den
                        // outer catch fallen, der dann fälschlich "Error
                        // during migration" loggt — die Migration hat
                        // aber gar nicht angefangen.
                        Timber.e(e, "Error restoring backup")
                        // Continue anyway - not critical
                    }
                }

                dataMigrationManager.runMigrationIfNeeded()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error during migration")
                // Try to record the error in ACRA
                try {
                    ACRA.errorReporter.putCustomData("migration_error", e.message ?: "unknown")
                    ACRA.errorReporter.putCustomData("migration_error_type", e::class.simpleName ?: "Throwable")
                } catch (ignored: Throwable) {
                    // ACRA might not be initialized or might fail
                }
            }
        }
    }

    private fun registerPackageUpdateReceiver() {
        try {
            val intentFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }

            registerReceiver(
                packageUpdateReceiver,
                intentFilter,
                RECEIVER_EXPORTED
            )
            Timber.d("[LIFECYCLE] PackageUpdateReceiver registered successfully.")
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[LIFECYCLE] Could not register PackageUpdateReceiver")
        }
    }

    private fun registerSystemWallpaperColorsListener() {
        // Plain Timber.e per Rule 9: KolibriLauncherApp is on the
        // crash-handling-infrastructure exception list. silentError
        // would throw in DEBUG and recurse into the same path it's
        // supposed to be the safety net for.
        try {
            val wallpaperManager = WallpaperManager.getInstance(this)

            wallpaperManager.addOnColorsChangedListener(
                { colors, which ->
                    if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                        emitSystemWallpaperColors(colors)
                    }
                },
                // Main-thread callback. The body is a single StateFlow
                // assignment; main-thread is fine.
                Handler(Looper.getMainLooper()),
            )

            // Initial poll AFTER registration. The OS may post a
            // duplicate emission for the already-current colours; the
            // signal's StateFlow.value = … is idempotent so it's
            // harmless. Polling after registering ensures we never
            // miss a colour change that races with onCreate.
            val initial = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            emitSystemWallpaperColors(initial)

            Timber.d("[LIFECYCLE] SystemWallpaperColors listener registered.")
        } catch (e: Throwable) {
            Timber.e(e, "[LIFECYCLE] Could not wire SystemWallpaperColorsSignal")
        }
    }

    private fun emitSystemWallpaperColors(colors: WallpaperColors?) {
        try {
            val domain = colors?.let {
                DomainWallpaperColors(
                    supportsDarkText = (it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0,
                    secondaryColorArgb = it.secondaryColor?.toArgb(),
                )
            }
            systemWallpaperColorsSignal.emit(domain)
        } catch (e: Throwable) {
            Timber.e(e, "[LIFECYCLE] Could not emit SystemWallpaperColors")
        }
    }

    private fun setupGlobalExceptionHandler() {
        try {
            defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                handleUncaughtException(thread, throwable)
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to setup global exception handler")
        }
    }

    /**
     * Handles uncaught exceptions with recovery attempts.
     * Tries to keep the launcher alive when possible.
     * Respects spam protection limits.
     */
    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        var handlerCalled = false

        try {
            Timber.e(throwable, "UNCAUGHT EXCEPTION in thread: ${thread.name}")

            // Check if we should send this report (spam protection)
            val shouldSend = CrashReportLimiter.shouldSendReport(throwable)
            if (!shouldSend) {
                Timber.d("Report blocked by spam protection for: ${throwable::class.simpleName}")
            }

            // Launcher-specific recovery for OutOfMemoryError
            if (throwable is OutOfMemoryError) {
                try {
                    // Emergency garbage collection
                    System.gc()
                    Runtime.getRuntime().gc()
                    Timber.w("Emergency GC triggered due to OutOfMemoryError")
                } catch (e: Throwable) {
                    // Even GC can fail - ignore
                }
            }

            // Call default exception handler once (ACRA will handle it)
            defaultExceptionHandler?.uncaughtException(thread, throwable)
            handlerCalled = true

        } catch (e: Throwable) {
            Timber.e(e, "Error in crash handler")

            // Only call default handler if not already called
            if (!handlerCalled) {
                try {
                    defaultExceptionHandler?.uncaughtException(thread, throwable)
                } catch (ignored: Throwable) {
                    // Nothing we can do at this point
                }
            }
        } finally {
            // Give system time to cleanup and ACRA to send report
            try {
                Thread.sleep(500)
            } catch (ignored: InterruptedException) {
                // Ignore
            }

            // Final resort - terminate process
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun setupStrictMode() {
        // WICHTIG: StrictMode darf NUR im Debug-Modus laufen!
        // Im Release kostet das Performance und nervt den User.
        if (!BuildConfig.DEBUG) return

        try {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll() // Erkennt Disk I/O, Network im Main Thread
                    .penaltyLog() // Schreibt in den Logcat
                    .penaltyFlashScreen()
                    // .penaltyDeath() // Optional: bei Main-Thread I/O hart crashen (sehr strikt!)
                    .build()
            )

            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll() // Erkennt Leaked SqlLite, Closable Objects, Activity Leaks
                    .penaltyLog()
                    // .penaltyDeath() // Optional: App hart crashen lassen bei Leaks (sehr strikt!)
                    .build()
            )

            Timber.d("StrictMode initialized successfully")

        } catch (e: Throwable) {
            // Sollte eigentlich nie passieren, aber gut für Defensive Programming
            Timber.e(e, "Error setting up StrictMode")
        }
    }

    /**
     * Walks `ApplicationExitInfo` for any ANRs the system recorded since
     * the last app start, forwards each to ACRA via a synthetic exception
     * that carries the system-supplied multi-thread dump as its message,
     * and advances [AnrReporter]'s persisted watermark per successful
     * forward.
     *
     * Replaces the previous `ANRWatchDog(5000)` live-sampling approach.
     * See [AnrReporter] KDoc for the trade-off (soft-ANR loss accepted in
     * exchange for richer system thread dumps + zero background overhead +
     * no unmaintained dependency).
     *
     * The ACRA dedup limiter from [CrashReportLimiter] still applies —
     * if a recurring ANR floods the same exception type, only the first
     * within the 24h cooldown window actually leaves the device.
     *
     * Plain `Timber.e` (not `silentError`) per CLAUDE.md Rule 9: this
     * Application is on the crash-handling-infrastructure exception list.
     */
    private fun reportPendingAnrsAsync() {
        applicationScope.launch(applicationExceptionHandler) {
            try {
                anrReporter.reportPendingAnrs { report ->
                    val description = report.description.ifBlank { "ANR" }
                    val synthetic = AnrException(
                        message = "$description\n\n${report.threadDump.orEmpty()}",
                    )
                    Timber.e(synthetic, "ANR (post-mortem from ApplicationExitInfo)")
                    try {
                        ACRA.errorReporter.handleException(synthetic)
                    } catch (e: Throwable) {
                        Timber.e(e, "Failed to forward ANR to ACRA")
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error walking pending ANRs")
            }
        }
    }

    /**
     * Marker exception type used solely to carry a post-mortem ANR report
     * into ACRA. Its stack trace is the *current* point in
     * `reportPendingAnrsAsync` — not the ANR site, which lives in the
     * `message` (system-supplied multi-thread dump). Distinct subclass so
     * [CrashReportLimiter]'s per-type cooldown buckets ANRs separately
     * from real exceptions.
     */
    private class AnrException(message: String) : RuntimeException(message)

    /**
     * Self-defense companion to [AnrReporter]: starts the
     * [RecoveryWatchdog] daemon thread that kills the process if the
     * main looper stops dispatching for 8 s.
     *
     * Started via `Handler.post { … }` rather than directly so the
     * first watchdog tick lands *after* `onCreate` returns and the
     * main thread re-enters its dispatch loop. Otherwise the heavy
     * cold-start work in this method (DataStore reads, migrations,
     * receiver registration) could legitimately block past 8 s and
     * trigger a kill-restart-loop on a HOME process that the OS
     * eagerly relaunches. See [RecoveryWatchdog] KDoc for the full
     * "why a Thread, not a coroutine" + "why 8 s" + AEI-categorization
     * caveat.
     *
     * Plain `Timber.e` (not `silentError`) per CLAUDE.md Rule 9: this
     * Application is on the crash-handling-infrastructure exception
     * list. The catch is defensive — `Handler.post` to the main
     * Looper is structurally safe, but an OOM during post-allocation
     * is the kind of edge that we don't want to crash startup over.
     */
    private fun startRecoveryWatchdogAfterBootstrap() {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    RecoveryWatchdog().start()
                } catch (e: Throwable) {
                    Timber.e(e, "Failed to start RecoveryWatchdog")
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to schedule RecoveryWatchdog start")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            applicationScope.cancel()
        } catch (e: Throwable) {
            Timber.e(e, "Error in onTerminate")
        }
    }

    /**
     * A specialized Timber.Tree that forwards exceptions to ACRA and includes a
     * diagnostic tool for improperly handled CancellationExceptions.
     *
     * Features spam protection: Each exception type is only sent once per 24 hours.
     *
     * THE PROBLEM:
     * Kotlin's `CancellationException` is used for control flow and is not a "true" error.
     * For performance reasons, it is often created WITHOUT a stack trace. This makes it
     * impossible to find the location of a faulty `catch (e: Exception)` block that
     * incorrectly logs it as an error.
     *
     * THE SOLUTION:
     * This tree identifies when a `CancellationException` is being logged. It wraps the
     * original exception inside a custom `UnhandledCancellationException`. Creating a new
     * exception at this moment FORCES the JVM to generate a fresh stack trace, pointing
     * directly to the problematic `catch` block.
     *
     * This turns a useless report (an exception with no trace) into an actionable one.
     */
    private class AcraTree : Timber.Tree() {

        class UnhandledCancellationException(message: String, cause: Throwable) : RuntimeException(message, cause)

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Only send warnings and errors with exceptions to ACRA
            if (priority < Log.WARN || t == null) {
                return
            }

            // Check spam protection BEFORE processing
            if (!CrashReportLimiter.shouldSendReport(t)) {
                // Report is blocked by spam protection - log locally but don't send to ACRA
                if (BuildConfig.DEBUG) {
                    Log.d("AcraTree", "Report blocked by spam protection: ${t::class.simpleName}")
                }
                return
            }

            // If it's a CancellationException, wrap it in our diagnostic exception
            if (t is java.util.concurrent.CancellationException) {
                val diagnosticException = UnhandledCancellationException(
                    "DIAGNOSIS: CancellationException was improperly caught as an error.", t
                )
                reportErrorToAcra(priority, tag, message, diagnosticException)
            } else {
                // For all other errors, maintain normal behavior
                reportErrorToAcra(priority, tag, message, t)
            }
        }

        private fun reportErrorToAcra(priority: Int, tag: String?, message: String, t: Throwable) {
            try {
                // First set custom data
                ACRA.errorReporter.putCustomData("log_priority", priority.toString())
                ACRA.errorReporter.putCustomData("log_tag", tag ?: "Unknown")
                ACRA.errorReporter.putCustomData("log_message", message)

                // Then submit the exception
                ACRA.errorReporter.handleSilentException(t)
            } catch (e: Throwable) {
                // Failsafe if ACRA is not initialized or crashes
                try {
                    Log.e("AcraTree", "Failed to report exception to ACRA", e)
                } catch (ignored: Throwable) {
                    // Even fallback logging can fail
                }
            }
        }
    }
}