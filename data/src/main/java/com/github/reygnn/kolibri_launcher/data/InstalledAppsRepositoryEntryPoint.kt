package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearSwipeActionsForPackageUseCase
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
     * Reconciles swipe assignments when a package is uninstalled. Invoked by
     * [PackageUpdateReceiver] on a genuine (non-replacing) package removal.
     */
    fun getClearSwipeActionsForPackageUseCase(): ClearSwipeActionsForPackageUseCase
}