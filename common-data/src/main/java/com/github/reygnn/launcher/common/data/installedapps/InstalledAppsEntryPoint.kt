package com.github.reygnn.launcher.common.data.installedapps

import com.github.reygnn.launcher.core.AppUpdateSignal
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Entry point so the non-Hilt [PackageUpdateReceiver] can reach the shared
 * [AppUpdateSignal] bus (SHARED_INSTALLED_APPS_SPEC §2 "Freshness").
 *
 * Lives in `:common-data` because the receiver — which is also here — references
 * it. It is `@InstallIn(SingletonComponent)`, so it is validated against BOTH
 * apps' Singleton graphs at compile time (an `@EntryPoint`'s `@InstallIn` is
 * discoverable in every app that depends on this module; unlike a binding module
 * it aggregates no `@Provides`). It therefore exposes ONLY [getAppUpdateSignal]:
 * `AppUpdateSignal` has an `@Inject @Singleton` constructor in `:core`, so it is
 * bindable in every app unconditionally. A `getInstalledAppsRepository()` accessor
 * (returning the shared `InstalledAppsRepository`) was deliberately NOT added — the
 * shared repository is bound per-app in each app's own DI (Wallpaper/TimeInfo
 * convention, no auto-aggregation), so it is not bound in an app that has not wired
 * it yet (Nyx until Step E), and exposing it here would make this entry point fail
 * to resolve in that app. The receiver only needs the signal bus.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InstalledAppsEntryPoint {
    fun getAppUpdateSignal(): AppUpdateSignal
}
