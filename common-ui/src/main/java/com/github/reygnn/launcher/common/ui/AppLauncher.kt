package com.github.reygnn.launcher.common.ui

import android.app.Activity
import com.github.reygnn.launcher.core.ComponentKey

/**
 * Launches another app's main activity and maps the system call's outcome to a
 * typed [AppLaunchResult]. Shared by both launchers (Kolibri + Nyx).
 *
 * Keyed by [ComponentKey] — the family's canonical, normalized component
 * identity (`:core`) — so both callers hand it the same thing: Kolibri passes
 * `AppInfo.key` (already long-form via `AppInfo.normalizedClassName`), Nyx passes
 * the tile's `ComponentKey`. The `LauncherApps` / `ActivityOptions` runtime glue
 * lives behind this injectable seam so the caller reacts to a typed result rather
 * than raw try/catch, and a test fake can return a chosen result without touching
 * the real system service (which Robolectric cannot make throw
 * `ActivityNotFoundException` — its `ShadowLauncherApps` does not implement
 * `startMainActivity`).
 */
interface AppLauncher {
    fun launch(activity: Activity, key: ComponentKey): AppLaunchResult
}
