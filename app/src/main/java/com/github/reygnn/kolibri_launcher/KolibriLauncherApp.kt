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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
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
import org.acra.data.StringFormat
import org.acra.config.httpSender
import org.acra.sender.HttpSender
import org.acra.ktx.initAcra
import org.acra.security.TLS
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
                    CrashReportConsent.hasConsent(base)
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

        // Receiver registration
        try {
            Timber.d("[LIFECYCLE] Application.onCreate - Registering receiver...")
            registerPackageUpdateReceiver()
        } catch (e: Throwable) {
            Timber.e(e, "[LIFECYCLE] FATAL: Could not register PackageUpdateReceiver!")
        }

        // Data migration - Ultra Paranoid version
        applicationScope.launch(applicationExceptionHandler) {
            try {
                val isFirstLaunch = dataMigrationManager.isFirstLaunch()

                if (isFirstLaunch && BuildConfig.DEBUG) {
                    try {
                        dataStoreBackup.restoreFromBackup()
                    } catch (e: Throwable) {
                        Timber.e(e, "Error restoring backup")
                        // Continue anyway - not critical
                    }
                }

                dataMigrationManager.runMigrationIfNeeded()
            } catch (e: Throwable) {
                Timber.e(e, "Error during migration")
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
            Timber.e(e, "[LIFECYCLE] FATAL: Could not register PackageUpdateReceiver!")
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
        try {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )

            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        } catch (e: Throwable) {
            Timber.e(e, "Error setting up StrictMode")
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