package com.github.reygnn.kolibri_launcher.ui.main

import android.content.ActivityNotFoundException

/**
 * Outcome of an app-launch attempt (see [AppLauncher]).
 *
 * Modeled as a sealed type in the same spirit as [AppLaunchAction]: the launch
 * side effect (`LauncherApps.startMainActivity`) is Activity-scope glue that
 * isn't JVM-testable, but the *decision* it feeds — which outcome should
 * trigger an orphan-reconcile reload — is. [shouldReconcile] captures that
 * decision so a plain JVM test can pin it.
 */
sealed interface AppLaunchResult {

    /**
     * True only for [ComponentGone]: a failed launch of a resolved component
     * is the definitive "this app is uninstalled" signal, so the caller kicks
     * an app-list refresh whose load-time sweep reconciles any stale
     * assignment pointing at it (TODO §25). A permission denial or an unknown
     * failure does NOT imply an uninstall and must not trigger a reconcile.
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
 * Runs [launch] — the actual `LauncherApps.startMainActivity` system call — and
 * maps its outcome to a typed [AppLaunchResult]. This is the four-category
 * System-API boundary frame lifted out of [AppLauncherImpl] so the
 * exception→result taxonomy can be pinned by a plain test, without the
 * un-mockable Activity / ActivityOptions / ComponentName statics that surround
 * the call site:
 *
 *  - success                     → [AppLaunchResult.Launched]
 *  - [ActivityNotFoundException] → [AppLaunchResult.ComponentGone] (the app is
 *    gone; this is the branch whose [AppLaunchResult.shouldReconcile] kicks the
 *    orphan-reconcile sweep that cleans stale swipe/favorite assignments)
 *  - [SecurityException]         → [AppLaunchResult.PermissionDenied]
 *  - any other [Throwable]       → [AppLaunchResult.Failed] (cause preserved)
 *
 * Behaviour is identical to the inline try/catch it replaces. The broad
 * `Throwable` catch is the sanctioned System-API-boundary form (Rule 11): a
 * real failure mode reported as a value, never a swallowed programmer error.
 */
internal fun runLaunchCatching(launch: () -> Unit): AppLaunchResult =
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
