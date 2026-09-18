package com.github.reygnn.launcher.common.ui

import android.content.ActivityNotFoundException

/**
 * Outcome of an app-launch attempt, shared by both launchers. The launch side
 * effect (`LauncherApps.startMainActivity` / `Context.startActivity`) is
 * Activity-scope glue that isn't JVM-testable, but the *decision* it feeds —
 * which outcome should trigger an orphan-reconcile reload — is; [shouldReconcile]
 * captures that so a plain JVM test can pin it.
 */
sealed interface AppLaunchResult {

    /**
     * True only for [ComponentGone]: a failed launch of a resolved component is
     * the definitive "this app is uninstalled" signal, so a caller may kick an
     * app-list refresh whose load-time sweep reconciles any stale assignment
     * pointing at it. A permission denial or an unknown failure does NOT imply an
     * uninstall and must not trigger a reconcile.
     */
    val shouldReconcile: Boolean get() = false

    /** The activity was started. */
    data object Launched : AppLaunchResult

    /** The component no longer resolves (`ActivityNotFoundException`) — gone. */
    data object ComponentGone : AppLaunchResult {
        override val shouldReconcile: Boolean get() = true
    }

    /** The launch was denied (`SecurityException`); the app is likely still installed. */
    data object PermissionDenied : AppLaunchResult

    /** Any other, unexpected launch failure. */
    data class Failed(val cause: Throwable) : AppLaunchResult
}

/**
 * Runs [launch] — the actual app-launch system call — and maps its outcome to a
 * typed [AppLaunchResult]:
 *
 *  - success                     → [AppLaunchResult.Launched]
 *  - [ActivityNotFoundException] → [AppLaunchResult.ComponentGone]
 *  - [SecurityException]         → [AppLaunchResult.PermissionDenied]
 *  - any other [Throwable]       → [AppLaunchResult.Failed] (cause preserved)
 *
 * The broad `Throwable` catch is the sanctioned System-API-boundary form (Rule 11):
 * a real failure mode reported as a value, never a swallowed programmer error.
 */
fun runLaunchCatching(launch: () -> Unit): AppLaunchResult =
    try {
        launch()
        AppLaunchResult.Launched
    } catch (e: ActivityNotFoundException) {
        AppLaunchResult.ComponentGone
    } catch (e: SecurityException) {
        AppLaunchResult.PermissionDenied
    } catch (e: Throwable) {
        AppLaunchResult.Failed(e)
    }
