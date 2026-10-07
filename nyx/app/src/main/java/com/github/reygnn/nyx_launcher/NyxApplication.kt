package com.github.reygnn.nyx_launcher

import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.github.reygnn.nyx_launcher.home.wallpaper.WallpaperLayerBitmapCache
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.feature.crashreporting.ingestion.AnrReporter
import com.github.reygnn.launcher.feature.crashreporting.resilience.AcraConfig
import com.github.reygnn.launcher.feature.crashreporting.resilience.LauncherAppBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Nyx application root. `@HiltAndroidApp` triggers Hilt's aggregating codegen and
 * member-injects [packageEvents] before [onCreate] returns.
 *
 * Crash reporting is the shared :feature-crashreporting pipeline, wired the same
 * way Kolibri wires it (rules 7-9): ACRA is initialised disabled in
 * [attachBaseContext] and only enabled for a stored Granted consent in
 * [onCreate]. Nyx supplies its own [AcraConfig] (endpoint from its BuildConfig,
 * fed by the shared root secrets.properties) and the shared [AnrReporter] as ANR
 * drainer. The Rule-7 guards (catch Throwable per block, Log before Timber is
 * wired, reportToAcra after) live in the shared LauncherAppBootstrap (1c-2).
 */
@HiltAndroidApp
class NyxApplication : Application() {

    @Inject
    lateinit var packageEvents: PackageEventCoordinator

    // Drives the shared installed-apps holder (Option A: nyx adopts the central
    // in-RAM holder + keep-last-good, SIA-INV-5). Started in onCreate; keeps the
    // holder warm so the drawer point-read and HomeViewModel.installedKeys read it.
    @Inject
    lateinit var installedAppsHolderPump: InstalledAppsHolderPump

    // App-scoped wallpaper layer bitmap cache (@Singleton). Released on memory
    // pressure / backgrounding below, since it holds up to ~64 MB of HARDWARE bitmaps
    // that are only needed while the home screen is visible.
    @Inject
    lateinit var wallpaperLayerCache: WallpaperLayerBitmapCache

    @Inject
    lateinit var wallpaperComposite: WallpaperComposite

    @Inject
    @IoDispatcher // without `field:` (Lint FieldSiteTargetOnQualifierAnnotation: redundant with KSP)
    lateinit var ioDispatcher: CoroutineDispatcher

    // Created on first use — after super.onCreate(), when Hilt has injected the dispatcher.
    private val applicationScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    // Post-mortem ANR reports (ApplicationExitInfo), shared with Kolibri since
    // SPEC_NYX_REWRITE 1c-1 — before, Nyx passed a no-op drainer and reported no ANRs.
    @Inject
    lateinit var anrReporter: AnrReporter

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // ACRA init (disabled until consent) + uncaught handler, Rule-7 guarded with a Log
        // fallback (Timber is not wired yet) — shared with Kolibri (SPEC_NYX_REWRITE 1c-2).
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

    override fun onCreate() {
        super.onCreate()

        // Shared bootstrap (SPEC_NYX_REWRITE 1c-2): :core logging seam, DEBUG trees
        // ("Nyx_<Class>" tags), StrictMode in DEBUG, then crash reporting + the post-mortem
        // ANR drain + watchdog — each block Rule-7 guarded (before: Timber unguarded, the
        // crash bootstrap caught with Log.e, no StrictMode).
        LauncherAppBootstrap.onCreate(
            app = this,
            isDebug = BuildConfig.DEBUG,
            logTag = LOG_TAG,
            tagPrefix = "Nyx",
            applicationScope = applicationScope,
            anrDrainer = anrReporter,
        )

        packageEvents.start()
        installedAppsHolderPump.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Rule 7: a throw from a cache op must not crash the process from a system callback.
        // Reported via reportToAcra (Rule 9: crash infrastructure) — silentError, used here
        // before 1c-2, throws in DEBUG from inside this safety net.
        LauncherAppBootstrap.guard("NyxApplication.onTrimMemory failed") { trimMemoryInternal(level) }
    }

    override fun onTerminate() {
        super.onTerminate()
        LauncherAppBootstrap.onTerminate(applicationScope)
    }

    private fun trimMemoryInternal(level: Int) {
        packageEvents.onTrimMemory(level)
        // Once the launcher's UI is hidden (a plain app switch already delivers
        // UI_HIDDEN, no memory pressure required) drop the wallpaper layer bitmaps: they
        // are only needed while the home is visible, and clear() only releases references
        // (never recycles), so a still-drawn bitmap is safe. A returning home re-decodes
        // lazily. Threshold aligned with the icon cache's full evict (LruBudget at
        // UI_HIDDEN) so ~64 MB of hardware bitmaps don't sit resident across an app switch
        // waiting for the later BACKGROUND level that only fires under memory pressure.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            wallpaperLayerCache.clear()
        }
        // Under real memory pressure (BACKGROUND and up, 3b-6) also drop the display composite
        // (~10 MB HARDWARE bitmap; the reference only, never recycled). Not at UI_HIDDEN: nothing
        // would re-warm it after every app switch — MainActivity.onStart re-warms after this.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            wallpaperComposite.invalidate(dropLuminance = false)
        }
    }

    private companion object {
        const val LOG_TAG = "NyxApplication"
    }
}
