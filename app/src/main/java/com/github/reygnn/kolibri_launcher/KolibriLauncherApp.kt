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
import android.util.Log
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import com.github.reygnn.kolibri_launcher.core.SystemWallpaperColorsSignal
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.crashreporting.resilience.CrashReportingBootstrap
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.data.PackageUpdateReceiver
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrReporter
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import com.github.reygnn.kolibri_launcher.ui.util.ToastErrorTree
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ULTRA CRASH-SAFE Application Class
 *
 * Multi-layer exception handling:
 * - All operations wrapped in try-catch with Throwable
 * - Crash reporting (init, uncaught handler, watchdog, ANR drain) delegated
 *   to `CrashReportingBootstrap`, privacy-by-default
 * - Safe package receiver registration
 *
 * This ensures the launcher stays alive even under extreme conditions
 * like OutOfMemoryError, StackOverflowError, or corrupted system state.
 */
@HiltAndroidApp
class KolibriLauncherApp : Application() {

    @Inject
    lateinit var anrReporter: AnrReporter
    @Inject
    lateinit var systemWallpaperColorsSignal: SystemWallpaperColorsSignal

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packageUpdateReceiver = PackageUpdateReceiver()

    /**
     * ACRA must be initialised here — `attachBaseContext` runs before `onCreate`
     * and before any other Application code, so it is the only safe place to
     * install the crash handler before something else can crash.
     *
     * The whole ACRA bootstrap (init, the §12 ordering, the X2-gated synchronous
     * consent read, the uncaught-handler install) lives in
     * [CrashReportingBootstrap.attachBaseContext] — see there for the
     * `runBlocking`/StrictMode rationale (§3.5) and the fail-closed sequence.
     * This override is thin glue: delegate inside the Rule-7 paranoia catch.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        if (base != null) {
            try {
                // Owns ACRA init + the X2-gated consent read + the uncaught
                // handler install, in the §12 order. See CrashReportingBootstrap.
                // Traced (cold-start): synchronous, runs before onCreate and
                // blocks the Main thread; wraps the ACRA-init + consent-read
                // sub-sections inside the delegate.
                LaunchTrace.section(LaunchTrace.Names.COLD_START_ATTACH) {
                    CrashReportingBootstrap.attachBaseContext(this, base)
                }
            } catch (e: Throwable) {
                // Ultra paranoid: even crash-reporting init must not crash the
                // app. Android Log fallback since Timber may not be wired yet.
                try {
                    Log.e("KolibriLauncher", "CRITICAL: Failed to initialize crash reporting", e)
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
        } catch (e: Throwable) {
            // Timber initialization failed - continue without it
            Log.e("KolibriLauncher", "Failed to initialize Timber", e)
        }

        // Setup StrictMode for debugging
        if (BuildConfig.DEBUG) {
            try {
                setupStrictMode()
            } catch (e: Throwable) {
                Timber.e(e, "Error setting up StrictMode")
            }
        }

        // Plant the delivery tree, drain post-mortem ANRs, start the watchdog
        // (§12·3). See CrashReportingBootstrap.
        // Traced (cold-start): synchronous Main-thread work in onCreate.
        LaunchTrace.section(LaunchTrace.Names.COLD_START_ONCREATE_BOOTSTRAP) {
            CrashReportingBootstrap.onCreate(this, applicationScope, anrReporter)
        }

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
        // Traced (cold-start): WallpaperManager IPC (getInstance +
        // getWallpaperColors) on the Main thread — a potential blocker.
        LaunchTrace.section(LaunchTrace.Names.COLD_START_WALLPAPER_COLORS) {
            registerSystemWallpaperColorsListener()
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

    override fun onTerminate() {
        super.onTerminate()
        try {
            applicationScope.cancel()
        } catch (e: Throwable) {
            Timber.e(e, "Error in onTerminate")
        }
    }

}