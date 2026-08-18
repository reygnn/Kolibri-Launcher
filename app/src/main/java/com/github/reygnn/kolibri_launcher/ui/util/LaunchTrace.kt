package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Trace

/**
 * Thin wrapper around [android.os.Trace] for measuring the tap→app-launch
 * latency path and the launcher's own process cold-start on-device with
 * Perfetto / Macrobenchmark.
 *
 * The launcher's own share of "tap a favorite/drawer entry → the target app's
 * process is asked to start" is a Main-thread pipeline that hops across a
 * `Channel` (ViewModel delegate → Activity collector). These named sections
 * pin the synchronous chunks of that pipeline; the Channel hop then shows up
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

        // --- Wallpaper full rebuild (jank investigation) ---
        // The rebuild is a suspend flow: bitmap decode off-main (IO), then
        // view mutation on the Main thread. These pin the two Main-thread
        // chunks (add_layer, apply) that can drop frames, plus the off-main
        // decode cost. Frame drops themselves come from the frametimeline
        // data source, not these slices.

        /** Per-layer `BitmapFactory` decode inside `withContext(IO)` — the
         * biggest time cost of a rebuild, but off the Main thread. */
        const val WALLPAPER_DECODE = "wallpaper_decode"

        /** `ZoomableImageView.addLayer` — Main-thread view mutation per
         * decoded layer. */
        const val WALLPAPER_ADD_LAYER = "wallpaper_add_layer"

        /** The reveal/transform/invalidate block (`applyUpdates`) — the
         * Main-thread work that produces the first rebuilt frame. */
        const val WALLPAPER_APPLY = "wallpaper_apply"

        // --- Wallpaper pinch/pan gesture redraw (jank investigation) ---
        // The wallpaper-rebuild trace showed a few janky frames during
        // continuous zoom/pan gestures (a separate path from the rebuild).
        // These pin the per-gesture Main-thread cost so it can be told apart
        // from GPU/RenderThread texture sampling of large bitmaps.

        /** `ZoomableImageView.onTouchEvent` gesture dispatch — matrix math +
         * invalidate per MotionEvent. */
        const val GESTURE_TOUCH = "gesture_touch"

        /** `ZoomableImageView.onDraw` multi-layer draw loop — per-frame
         * `drawBitmap` command recording during a gesture (fires only on
         * invalidate, i.e. during gestures/rebuilds, not when idle). */
        const val GESTURE_ONDRAW = "gesture_ondraw"

        // --- Wallpaper save (pan/scale/save manual-trace investigation) ---

        /** The save-FAB tap handler in `WallpaperEditController` — the
         * SYNCHRONOUS Main-thread cost of committing an edit session
         * (read view transforms + `decide` + dispatch + commit). The actual
         * DataStore persist is launched async off this slice, so it does NOT
         * span the write; this pins only the on-tap main-thread chunk that
         * could drop a frame. */
        const val WALLPAPER_SAVE = "wallpaper_save"

        // --- Composite warm (in-memory fill, WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4) ---
        // ASYNC sections: the warm suspends / hops threads (flatten on Main, decodes on IO),
        // so the sync `section` (thread-local begin/end) would mis-report. Measured on device
        // via the `:macrobenchmark` TraceSectionMetric.

        /** The whole background composite warm: flatten -> luminance -> HARDWARE copy ->
         * cache put. Off the critical path (once per process / edit / rotate). */
        const val WALLPAPER_WARM = "wallpaper_warm"

        /** Just the flatten (SOFTWARE decode of N layers + compose) — the dominant part of
         * [WALLPAPER_WARM]. */
        const val WALLPAPER_FLATTEN = "wallpaper_flatten"
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

    /**
     * Async trace section — for spans that SUSPEND or hop threads, where the thread-local sync
     * [section] would mis-report (begin and end may land on different threads). Begin/end are
     * matched by [cookie]; the caller MUST balance them (try/finally). Cheap no-op when no tracer
     * is attached, like [section]. `beginAsyncSection` needs API 29+ (minSdk 36 here).
     */
    fun beginAsync(name: String, cookie: Int) = Trace.beginAsyncSection(name, cookie)

    fun endAsync(name: String, cookie: Int) = Trace.endAsyncSection(name, cookie)
}
