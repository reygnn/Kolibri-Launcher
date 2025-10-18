package com.github.reygnn.kolibri_launcher

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Ein Hilt EntryPoint, der es Klassen, die nicht von Hilt verwaltet werden
 * (wie BroadcastReceiver), ermöglicht, auf Hilt-Singletons zuzugreifen.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InstalledAppsManagerEntryPoint {
    fun getInstalledAppsManager(): InstalledAppsManager
    fun getAppUpdateSignal(): AppUpdateSignal
}