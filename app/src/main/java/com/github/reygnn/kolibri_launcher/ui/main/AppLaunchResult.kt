package com.github.reygnn.kolibri_launcher.ui.main

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
