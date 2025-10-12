/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.R.attr.enabled
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
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

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        if (base != null) {
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
                    ReportField.ANDROID_VERSION,
                    ReportField.APP_VERSION_CODE,
                    ReportField.APP_VERSION_NAME,
                    ReportField.BRAND,
                    ReportField.PHONE_MODEL,
                    ReportField.STACK_TRACE
                )
            }

            val userHasGivenConsent = runBlocking {
                CrashReportConsent.hasConsent(base)
            }
            ACRA.errorReporter.setEnabled(userHasGivenConsent)
            Timber.i("ACRA initialized. Consent status: $userHasGivenConsent")
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Setup Timber
        if (BuildConfig.DEBUG) {
            // Custom DebugTree mit kurzen TAGs
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String {
                    val className = element.className
                        .substringAfterLast('.')  // Nur Klassenname ohne Package
                        .replace(Regex("\\$\\d+"), "")  // Entferne $1, $2, etc.
                        .replace("$", ".")  // Ersetze $ durch . für innere Klassen
                    return "Kolibri_$className"
                }
            })

            Timber.plant(ToastErrorTree())   // Toasts nur in Debug
        } else {
            Timber.plant(ReleaseTree())
        }

        // Setup global exception handler (optional, da ACRA das übernimmt)
        if (BuildConfig.DEBUG) {
            setupGlobalExceptionHandler()
        }

        // Setup StrictMode for debugging
        if (BuildConfig.DEBUG) {
            setupStrictMode()
        }

        // Receiver registration
        Timber.d("[LIFECYCLE] Application.onCreate - Registering receiver...")
        registerPackageUpdateReceiver()

        // Data migration
        applicationScope.launch {
            try {
                val isFirstLaunch = dataMigrationManager.isFirstLaunch()

                if (isFirstLaunch && BuildConfig.DEBUG) {
                    dataStoreBackup.restoreFromBackup()
                }

                dataMigrationManager.runMigrationIfNeeded()
            } catch (e: Exception) {
                Timber.e(e, "Error during migration")   // auch als Toast
                // ACRA-Custom-Daten hinzufügen
                ACRA.errorReporter.putCustomData("migration_error", "true")
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
        } catch (e: Exception) {
            Timber.e(e, "[LIFECYCLE] FATAL: Could not register PackageUpdateReceiver!")   // auch als Toast
        }
    }

    private fun setupGlobalExceptionHandler() {
        try {
            defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                handleUncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup global exception handler")   // auch als Toast
        }
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Timber.e(throwable, "UNCAUGHT EXCEPTION in thread: ${thread.name}")   // auch als Toast

            // ACRA übernimmt das Crash-Handling
            defaultExceptionHandler?.uncaughtException(thread, throwable)

        } catch (e: Exception) {
            Timber.e(e, "Error in crash handler")   // auch als Toast
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        } finally {
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
        } catch (e: Exception) {
            Timber.e(e, "Error setting up StrictMode")   // auch als Toast
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            applicationScope.cancel()
        } catch (e: Exception) {
            Timber.e(e, "Error in onTerminate")   // auch als Toast
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.DEBUG || priority == Log.VERBOSE || priority == Log.INFO) {
                return
            }

            // ACRA verwenden statt Firebase
            if (t != null) {
                // Exception an ACRA melden
                ACRA.errorReporter.handleSilentException(t)
                ACRA.errorReporter.putCustomData("log_priority", priority.toString())
                ACRA.errorReporter.putCustomData("log_tag", tag ?: "Unknown")
                ACRA.errorReporter.putCustomData("log_message", message)
            }
        }
    }
}