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
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import com.github.reygnn.kolibri_launcher.core.SystemWallpaperColorsSignal
import com.github.reygnn.kolibri_launcher.data.InstalledAppsRepositoryEntryPoint
import com.github.reygnn.launcher.common.data.installedapps.PackageUpdateReceiver
import com.github.reygnn.launcher.core.wallpaper.DomainWallpaperColors
import com.github.reygnn.launcher.common.ui.LaunchTrace
import com.github.reygnn.launcher.feature.crashreporting.ingestion.AnrReporter
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.feature.crashreporting.resilience.AcraConfig
import com.github.reygnn.launcher.feature.crashreporting.resilience.LauncherAppBootstrap
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

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

    companion object {
        private const val LOG_TAG = "KolibriLauncher"
    }

    @Inject
    lateinit var anrReporter: AnrReporter
    @Inject
    lateinit var systemWallpaperColorsSignal: SystemWallpaperColorsSignal

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    // Created on first use — after super.onCreate(), when Hilt has injected the dispatcher.
    private val applicationScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    /**
     * Last known locale tags, to detect a system locale change in
     * [onConfigurationChanged] (AUDIT-19 F5). Seeded in [onCreate].
     */
    private var lastLocaleTags: String? = null

    /**
     * ACRA must be initialised here — `attachBaseContext` runs before `onCreate`
     * and before any other Application code, so it is the only safe place to
     * install the crash handler before something else can crash.
     *
     * ACRA init (the §12 ordering, the A1 disable-after-init) and the
     * uncaught-handler install live in [CrashReportingBootstrap.attachBaseContext].
     * The X2-gated synchronous consent read is NOT here — it moved to
     * [CrashReportingBootstrap.onCreate] on 2026-08-14, because
     * `context.consentDataStore` dereferences `applicationContext`, which is null
     * during `attachBaseContext`; see there for the `runBlocking`/StrictMode
     * rationale (§3.5) and the fail-closed sequence.
     * This override is thin glue: delegate inside the Rule-7 paranoia catch.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // ACRA init (disabled until consent) + uncaught handler, Rule-7 guarded with a Log
        // fallback (Timber is not wired yet) — shared with Nyx (1c-2). The consent read
        // runs later in onCreate (applicationContext is null during attach). Traced
        // (cold start): synchronous, runs before onCreate and blocks the Main thread.
        LaunchTrace.section(LaunchTrace.Names.COLD_START_ATTACH) {
            LauncherAppBootstrap.attachBaseContext(
                this,
                base,
                AcraConfig(
                    buildConfigClass = BuildConfig::class.java,
                    url = BuildConfig.ACRA_URL,
                    login = BuildConfig.ACRA_LOGIN,
                    password = BuildConfig.ACRA_PASSWORD,
                ),
                LOG_TAG,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Shared bootstrap (SPEC_NYX_REWRITE 1c-2): :core logging seam, DEBUG trees
        // ("Kolibri_<Class>" tags), StrictMode in DEBUG, then crash reporting + the
        // post-mortem ANR drain + watchdog — each block Rule-7 guarded. Only the
        // crash-bootstrap call is traced (cold start: synchronous Main-thread work).
        LauncherAppBootstrap.onCreate(
            app = this,
            isDebug = BuildConfig.DEBUG,
            logTag = LOG_TAG,
            tagPrefix = "Kolibri",
            applicationScope = applicationScope,
            anrDrainer = anrReporter,
            traceBootstrap = { bootstrap ->
                LaunchTrace.section(LaunchTrace.Names.COLD_START_ONCREATE_BOOTSTRAP) { bootstrap() }
            },
        )

        // Receiver registration — der Helper hat seinen eigenen catch(Throwable)
        // mit silentError, also kann hier nichts entkommen. Ein zusätzlicher
        // outer try/catch wäre toter Code.
        Timber.d("[LIFECYCLE] Application.onCreate - Registering receiver...")
        registerPackageUpdateReceiver()

        // Baseline for the locale-change detection in onConfigurationChanged
        // (AUDIT-19 F5). Read here so the first real config change has something
        // to compare against.
        lastLocaleTags = resources.configuration.locales.toLanguageTags()

        // v4 one-shot cleanup (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC §6): the on-disk composite
        // tier was removed; delete its now-orphaned dir once, off-main. NOT a data migration —
        // the composite is a derived artifact (Rule 5). deleteRecursively on a missing dir is a
        // cheap no-op, so no "run once" flag is needed; it self-quiesces after the first launch.
        applicationScope.launch {
            runCatching { java.io.File(filesDir, "wallpaper_composite").deleteRecursively() }
        }

        // Wire SystemWallpaperColorsSignal to WallpaperManager. Drives the
        // AppDrawer's AUTO surface mode (and any future surface that wants
        // to react to system-wallpaper colour-hint changes). Listener +
        // initial poll, both inside the same outer catch — Rule 7 paranoia
        // applies; the catch reports via reportToAcra (Rule 9, no DEBUG throw).
        // No unregister: Application.onTerminate isn't called on real
        // devices, and the signal is a process-lifetime singleton.
        //
        // OFF the cold-start critical path: getWallpaperColors(FLAG_SYSTEM) is a
        // blocking WallpaperManager IPC, and the Home first frame does NOT need the
        // system-colour hints — only the AppDrawer AUTO surface does, on drawer open.
        // Running it on applicationScope (IO) instead of synchronously on the Main
        // thread in onCreate keeps the IPC out of the first-frame (TTID) budget. The
        // signal starts null (SystemWallpaperColorsSignal — consumers already treat
        // null as "no signal / fall back"), and the poll seeds it a few ms later, long
        // before a user gesture can open the drawer. The trace section stays so the
        // cost remains visible on the Perfetto timeline — now on the IO thread, not
        // blocking the frame.
        applicationScope.launch {
            LaunchTrace.section(LaunchTrace.Names.COLD_START_WALLPAPER_COLORS) {
                registerSystemWallpaperColorsListener()
            }
        }
    }

    /**
     * A system locale change re-localises third-party launcher labels (loaded
     * via `loadLabel`). The [PackageUpdateReceiver] only covers package events,
     * and the `LauncherViewModel` survives the configuration change, so nothing
     * re-enumerates on its own — trigger a refresh here (AUDIT-19 F5, the path
     * that replaced the per-`onStart` re-enumeration).
     *
     * Reports via `reportToAcra` per Rule 9 (crash-infra: no DEBUG throw, so it
     * can't recurse into the safety net). The guarded body is synchronous —
     * `applicationScope.launch` only schedules; the suspend `triggerAppsUpdate`
     * runs inside the coroutine, not in the try — so no `CancellationException`
     * arm is needed.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val newTags = newConfig.locales.toLanguageTags()
            if (lastLocaleTags != null && newTags != lastLocaleTags) {
                Timber.d("[LIFECYCLE] Locale changed ($lastLocaleTags -> $newTags) — refreshing app labels")
                val repository = EntryPointAccessors.fromApplication(
                    this,
                    InstalledAppsRepositoryEntryPoint::class.java,
                ).getInstalledAppsRepository()
                applicationScope.launch { repository.triggerAppsUpdate() }
            }
            lastLocaleTags = newTags
        } catch (e: Throwable) {
            TimberWrapper.reportToAcra(e, "Error handling locale change for app-list refresh")
        }
    }

    private fun registerPackageUpdateReceiver() {
        try {
            // Shared registration (filter + NOT_EXPORTED flag single-sourced in :common-data,
            // so kolibri and nyx can't drift). ACTION_PACKAGE_CHANGED is included there to make
            // an app/component enable-disable reactive (AUDIT-19 F5).
            PackageUpdateReceiver.register(this)
            Timber.d("[LIFECYCLE] PackageUpdateReceiver registered successfully.")
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[LIFECYCLE] Could not register PackageUpdateReceiver")
        }
    }

    private fun registerSystemWallpaperColorsListener() {
        // reportToAcra (ACRA_REPORT intent tag) per Rule 9: KolibriLauncherApp is
        // on the crash-handling-infrastructure exception list. silentError would
        // throw in DEBUG and recurse into the same path it's supposed to be the
        // safety net for; reportToAcra reports without the DEBUG throw.
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
            TimberWrapper.reportToAcra(e, "[LIFECYCLE] Could not wire SystemWallpaperColorsSignal")
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
            TimberWrapper.reportToAcra(e, "[LIFECYCLE] Could not emit SystemWallpaperColors")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        LauncherAppBootstrap.onTerminate(applicationScope)
    }

}