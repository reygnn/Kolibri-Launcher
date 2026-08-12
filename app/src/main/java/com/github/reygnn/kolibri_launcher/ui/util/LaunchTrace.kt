package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Trace

/**
 * Thin wrapper around [android.os.Trace] for measuring the tap→app-launch
 * latency path and the launcher's own process cold-start on-device with
 * Perfetto / Macrobenchmark.
 *
 * The launcher's own share of "tap a favorite/drawer entry → the target app's
 * process is asked to start" is a Main-thread pipeline that hops across a
 * `SharedFlow` (ViewModel delegate → Activity collector). These named sections
 * pin the synchronous chunks of that pipeline; the SharedFlow hop then shows up
 * as the GAP between two sections on the Perfetto timeline, which is exactly the
 * dispatch latency we want to find. Everything after [Names.START_MAIN_ACTIVITY]
 * (process fork, the target app's `Application.onCreate`, first frame) is the
 * foreign app's cold start and NOT the launcher's to optimise — the system view
 * in the same trace covers it.
 *
 * These calls are near-free when nobody is tracing: the framework gates on a
 * cheap atomic before doing any work. No DEBUG guard is needed, so the sections
 * are present in release too (that is the point — Macrobenchmark measures the
 * release build, enabled by the `<profileable>` manifest flag).
 *
 * Section names are Perfetto slice labels: keep them short, stable, and
 * greppable (they are matched verbatim by `TraceSectionMetric` if a
 * Macrobenchmark is added later).
 */
object LaunchTrace {

    /** Stable slice names for the launch + cold-start paths. Referenced by
     * tooling (matched verbatim by `TraceSectionMetric`). */
    object Names {
        /** The tap reached the ViewModel-side handler (`onAppClicked`). */
        const val TAP = "app_launch_tap"

        /** The Activity collector is handling the `LaunchApp` event
         * (decide + optional drawer `popBackStack` + `launchApp`). */
        const val DISPATCH = "app_launch_dispatch"

        /** The actual `LauncherApps.startMainActivity` binder call. */
        const val START_MAIN_ACTIVITY = "app_launch_startMainActivity"

        /** `NavController.navigate` into the app drawer (drawer-open path). */
        const val DRAWER_OPEN = "drawer_open_navigate"

        // --- Launcher process cold start (Application bootstrap) ---
        // These pin the synchronous Main-thread bootstrap chain that runs
        // before the first launcher frame. The `android_startups` trace metric
        // already gives total startup; these break down the launcher's own
        // share. Nesting: COLD_START_ATTACH wraps ACRA_INIT + CONSENT_READ.

        /** The whole `CrashReportingBootstrap.attachBaseContext` delegate
         * (runs before `onCreate`, blocks the Main thread). */
        const val COLD_START_ATTACH = "cold_start_attach"

        /** `initAcra { … }` — ACRA config build + init (reflection-heavy). */
        const val COLD_START_ACRA_INIT = "cold_start_acra_init"

        /** `runBlocking { ConsentBootstrap.readDecision(base) }` — the
         * synchronous DataStore consent read on the Main thread. Prime
         * suspect for cold-start latency. */
        const val COLD_START_CONSENT_READ = "cold_start_consent_read"

        /** `CrashReportingBootstrap.onCreate` — plant delivery tree, drain
         * post-mortem ANRs, arm the watchdog. */
        const val COLD_START_ONCREATE_BOOTSTRAP = "cold_start_oncreate_bootstrap"

        /** `registerSystemWallpaperColorsListener` — WallpaperManager IPC
         * (getInstance + getWallpaperColors) on the Main thread. */
        const val COLD_START_WALLPAPER_COLORS = "cold_start_wallpaper_colors"
    }

    /**
     * Runs [block] inside a synchronous trace section [name]. The section is
     * balanced even if [block] throws (`finally`) — this is section
     * bookkeeping, not a swallowing catch (Rule 11 exception: no `catch`).
     *
     * Must begin and end on the same thread, so only wrap SYNCHRONOUS work —
     * never a block that suspends, or the section would span the suspension and
     * mis-report the duration.
     */
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }
}
