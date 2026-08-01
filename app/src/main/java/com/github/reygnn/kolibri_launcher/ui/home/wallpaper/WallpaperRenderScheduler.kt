package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Single-slot, latest-wins scheduler for the wallpaper render.
 *
 * Enforces one invariant: launching a new render cancels the previous
 * in-flight one, so a slower decode of an older [WallpaperState] can never
 * land on top of a newer wallpaper. Whichever render was requested last is
 * the one whose result reaches the view.
 *
 * == WHY THIS EXISTS AS A CLASS ==
 * The "cancel the old job, launch the new one" sequence used to be two
 * inline lines in `HomeFragment.updateWallpaper`. That put a real
 * correctness decision inside an Android-runtime class where nothing
 * pinned it — deleting the cancel would silently reintroduce the
 * stale-render race (an old, slow decode overwriting a newer wallpaper),
 * and no test would go red. Lifting it here (Rule 10) makes the invariant
 * a plain-Kotlin unit that `WallpaperRenderSchedulerTest` can pin directly,
 * happy path and stale-render sad path.
 *
 * The scheduler is stateless apart from the single [job] handle. It does
 * not own a scope: the caller passes the scope per [render] call, because
 * in the Fragment that scope is `viewLifecycleOwner.lifecycleScope`, which
 * is recreated on every view lifecycle. Tying the render to that scope also
 * means `onDestroyView` cancels any in-flight render for free; [cancel] is
 * the explicit companion for releasing the handle during teardown.
 */
class WallpaperRenderScheduler {

    private var job: Job? = null

    /**
     * Cancel any in-flight render and launch [block] on [scope] as the new
     * one. Latest wins: the previous render, if still running, is cancelled
     * before this one starts, so its result can never reach the view.
     */
    fun render(scope: CoroutineScope, block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    /**
     * Cancel the in-flight render, if any, and drop the handle. Called from
     * `onDestroyView`; the scope cancellation already stops the coroutine,
     * this additionally releases the stale [Job] reference.
     */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
