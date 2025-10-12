/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.ExperimentalCoroutinesApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InstalledAppsManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
    private val appNamesManager: AppNamesRepository,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>
) : InstalledAppsRepository {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val appsStateFlow: StateFlow<List<AppInfo>> = appsUpdateTrigger
        .onStart {
            try {
                emit(Unit)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error emitting initial trigger")
            }
        }
        .flatMapLatest {
            loadAppsFromPackageManager()
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    override fun getInstalledApps(): Flow<List<AppInfo>> {
        return appsStateFlow
    }

    /**
     * Löst eine manuelle Aktualisierung der App-Liste aus.
     */
    suspend fun triggerAppsUpdate() {
        try {
            Timber.d("App update triggered.")
            Timber.d("[DATAFLOW] 2. Update triggered in InstalledAppsManager.")
            appsUpdateTrigger.emit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error triggering apps update")
        }
    }

    /**
     * Eine private Funktion, die den eigentlichen Ladevorgang in einem Flow kapselt.
     */
    private fun loadAppsFromPackageManager(): Flow<List<AppInfo>> = flow {
        Timber.d("!!! PROBE: Loading apps from PackageManager... Expensive operation is RUNNING!")

        try {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = try {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error querying intent activities")
                emptyList()
            }

            val freshApps = processResolveInfoList(resolveInfoList)
            Timber.d("[DATAFLOW] 3. Manager is emitting a new list. Size: ${freshApps.size}")

            emit(freshApps)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error loading apps for Flow.")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Verarbeitet die rohe Liste von ResolveInfo in eine saubere Liste von AppInfo-Objekten.
     */
    internal suspend fun processResolveInfoList(resolveInfoList: List<ResolveInfo>): List<AppInfo> {
        val appList = mutableListOf<AppInfo>()

        for (resolveInfo in resolveInfoList) {
            try {
                resolveInfo.activityInfo?.let { activityInfo ->
                    val systemName = try {
                        resolveInfo.loadLabel(packageManager).toString()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error loading label for ${activityInfo.packageName}")
                        activityInfo.packageName // Fallback auf packageName
                    }

                    val customDisplayName = try {
                        appNamesManager.getDisplayNameForPackage(
                            activityInfo.packageName,
                            systemName
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error getting custom display name for ${activityInfo.packageName}")
                        systemName // Fallback auf systemName
                    }

                    val appInfo = AppInfo(
                        originalName = systemName,
                        displayName = customDisplayName,
                        packageName = activityInfo.packageName,
                        className = activityInfo.name,
                        isSystemApp = false,
                        isFavorite = false
                    )
                    appList.add(appInfo)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not process app: ${resolveInfo.activityInfo?.packageName}")
            }
        }

        // Sortierung mit Fehlerbehandlung
        val sortedList = try {
            appList.sortedBy { it.displayName.lowercase() }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error sorting app list, returning unsorted")
            appList
        }

        Timber.d("${sortedList.size} apps processed and sorted.")
        return sortedList
    }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}