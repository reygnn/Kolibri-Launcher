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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val appsStateFlow: StateFlow<List<AppInfo>> = appsUpdateTrigger
        .onStart {
            try {
                emit(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error emitting initial trigger")
                // Trotzdem versuchen weiterzumachen
                emit(Unit)
            }
        }
        .flatMapLatest {
            loadAppsFromPackageManager()
        }
        .catch { e ->
            try {
                TimberWrapper.silentError(e, "Error in apps state flow, emitting empty list")
                emit(emptyList())
            } catch (catchError: Throwable) {
                TimberWrapper.silentError(catchError, "CRITICAL: Error in catch block")
                // Letzte Verteidigungslinie: Leere Liste
                emit(emptyList())
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    override fun getInstalledApps(): Flow<List<AppInfo>> {
        return appsStateFlow
    }

    override suspend fun triggerAppsUpdate() {
        try {
            Timber.d("App update triggered.")
            Timber.d("[DATAFLOW] 2. Update triggered in InstalledAppsManager.")
            appsUpdateTrigger.emit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error triggering apps update")
            // Nicht weiteren Error werfen - Update-Fehler sollten nicht crashen
        }
    }

    private fun loadAppsFromPackageManager(): Flow<List<AppInfo>> = flow {
        try {
            Timber.d("!!! PROBE: Loading apps from PackageManager... Expensive operation is RUNNING!")

            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = try {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error querying intent activities")
                emptyList()
            }

            val freshApps = try {
                processResolveInfoList(resolveInfoList)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error processing resolve info list")
                emptyList()
            }

            Timber.d("[DATAFLOW] 3. Manager is emitting a new list. Size: ${freshApps.size}")
            emit(freshApps)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error loading apps for Flow")
            emit(emptyList())
        }
    }
        .catch { e ->
            try {
                TimberWrapper.silentError(e, "Flow catch: Error in loadAppsFromPackageManager")
                emit(emptyList())
            } catch (catchError: Throwable) {
                TimberWrapper.silentError(catchError, "CRITICAL: Error in flow catch block")
            }
        }
        .flowOn(Dispatchers.IO)

    suspend fun processResolveInfoList(resolveInfoList: List<ResolveInfo>): List<AppInfo> {
        val appInfoList = mutableListOf<AppInfo>()

        for (info in resolveInfoList) {
            try {
                val activityInfo = info.activityInfo
                if (activityInfo == null) {
                    TimberWrapper.silentError("ActivityInfo is null for ResolveInfo")
                    continue
                }

                val packageName = activityInfo.packageName
                val className = activityInfo.name

                val originalName = try {
                    val label = info.loadLabel(packageManager).toString()
                    label.ifBlank {
                        packageName  // ✅ Fallback bei leerem Label
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error loading label for $packageName")
                    packageName
                }

                val displayName = try {
                    appNamesManager.getDisplayNameForPackage(packageName, originalName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error getting display name for $packageName")
                    originalName
                }

                appInfoList.add(
                    AppInfo(
                        originalName = originalName,
                        displayName = displayName,
                        packageName = packageName,
                        className = className
                    )
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error processing ResolveInfo")
            }
        }

        return try {
            appInfoList.sortedBy { it.displayName.lowercase() }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error sorting app list")
            appInfoList
        }
    }

    override fun purgeRepository() {
    }
}