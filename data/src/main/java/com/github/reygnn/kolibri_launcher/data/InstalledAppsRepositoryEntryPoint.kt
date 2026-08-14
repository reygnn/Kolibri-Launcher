package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Ein Hilt EntryPoint, der es Klassen, die nicht von Hilt verwaltet werden
 * (wie BroadcastReceiver), ermöglicht, auf Hilt-Singletons zuzugreifen.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InstalledAppsRepositoryEntryPoint {
    fun getAppUpdateSignal(): AppUpdateSignal

    /**
     * The installed-apps repository, so a non-Hilt caller can trigger a
     * re-enumeration directly. Used by [com.github.reygnn.kolibri_launcher.KolibriLauncherApp]
     * on a system locale change (AUDIT-19 F5): locale is not a package event, so
     * it cannot flow through [getAppUpdateSignal]; the app calls
     * [InstalledAppsRepository.triggerAppsUpdate] to refresh the now-stale
     * `loadLabel` results.
     */
    fun getInstalledAppsRepository(): InstalledAppsRepository
}