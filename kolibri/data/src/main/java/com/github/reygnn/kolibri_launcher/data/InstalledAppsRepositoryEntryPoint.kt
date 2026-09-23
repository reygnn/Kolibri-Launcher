package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.launcher.core.InstalledAppsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point letting the non-Hilt `KolibriLauncherApp.onCreate` reach the
 * shared installed-apps repository so it can force a re-enumeration on a system
 * locale change (AUDIT-19 F5): locale is not a package event, so it does not flow
 * through the broadcast → [com.github.reygnn.launcher.core.AppUpdateSignal]
 * pipeline; the app calls [InstalledAppsRepository.triggerAppsUpdate] directly.
 *
 * Since C3 this resolves against the shared `:core` [InstalledAppsRepository] (the
 * LauncherApps-backed motor in `:common-data`). The package-broadcast path uses the
 * shared `:common-data` `InstalledAppsEntryPoint` instead, so this one no longer
 * exposes the signal bus.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InstalledAppsRepositoryEntryPoint {
    fun getInstalledAppsRepository(): InstalledAppsRepository
}
