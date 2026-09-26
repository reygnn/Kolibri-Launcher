package com.github.reygnn.nyx_launcher

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.github.reygnn.nyx_launcher.home.wallpaper.WallpaperLayerBitmapCache
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.feature.crashreporting.ToastErrorTree
import com.github.reygnn.launcher.feature.crashreporting.wireKolibriLogToTimber
import com.github.reygnn.launcher.feature.crashreporting.resilience.AcraConfig
import com.github.reygnn.launcher.feature.crashreporting.resilience.CrashReportingBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Nyx application root. `@HiltAndroidApp` triggers Hilt's aggregating codegen and
 * member-injects [packageEvents] before [onCreate] returns.
 *
 * Crash reporting is the shared :feature-crashreporting pipeline, wired the same
 * way Kolibri wires it (rules 7-9): ACRA is initialised disabled in
 * [attachBaseContext] and only enabled for a stored Granted consent in
 * [onCreate]. Nyx supplies its own [AcraConfig] (endpoint from its BuildConfig,
 * fed by the shared root secrets.properties) and a [NoOpAnrDrainer] (no ANR
 * watermark store yet). The per-block try/catch(Throwable) guards mirror
 * Kolibri's crash-safe Application (rule 7).
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val anrDrainer = NoOpAnrDrainer()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            try {
                CrashReportingBootstrap.attachBaseContext(
                    this,
                    AcraConfig(
                        buildConfigClass = BuildConfig::class.java,
                        url = BuildConfig.ACRA_URL,
                        login = BuildConfig.ACRA_LOGIN,
                        password = BuildConfig.ACRA_PASSWORD,
                    ),
                )
            } catch (e: Throwable) {
                Log.e("NyxApplication", "ACRA attachBaseContext failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Hand BuildConfig.DEBUG to :core's TimberWrapper and wire KolibriLog to Timber
        // (both pure-Kotlin in :core with no Timber on their classpath). Shared with kolibri
        // so the routing can't drift.
        wireKolibriLogToTimber(BuildConfig.DEBUG)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            // Feed ERROR logs to the ErrorEventBus so BaseActivity surfaces a dev error
            // toast (DEBUG only; SILENT_ERROR-tagged entries are suppressed there).
            Timber.plant(ToastErrorTree())
        }

        try {
            CrashReportingBootstrap.onCreate(this, applicationScope, anrDrainer)
        } catch (e: Throwable) {
            Log.e("NyxApplication", "ACRA onCreate failed", e)
        }

        packageEvents.start()
        installedAppsHolderPump.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Rule 7: a throw from a cache op must not crash the process from a system callback.
        try {
            trimMemoryInternal(level)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "NyxApplication.onTrimMemory failed")
        }
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
    }
}
