/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central state manager for installed applications.
 *
 * This singleton manages the application list state and provides a fail-safe mechanism
 * to ensure the UI always has access to valid app data, even in error scenarios.
 *
 * **Key responsibilities:**
 * - Maintains the current list of installed apps via a [StateFlow]
 * - Caches the last successful app list as fallback
 * - Provides thread-safe access to app data
 * - Handles errors gracefully without crashing the app
 *
 * **Thread-safety:**
 * The manager uses [MutableStateFlow] for reactive updates and a volatile cache variable
 * to ensure visibility across threads. All operations are protected with try-catch blocks.
 *
 * **Error handling:**
 * If an error occurs during updates or retrieval, the manager falls back to the last
 * known good state ([lastSuccessfulAppList]) to prevent UI disruptions.
 *
 * @property rawAppsFlow Observable flow of the current app list for reactive UI updates
 */
@Singleton
class InstalledAppsStateManager @Inject constructor() : InstalledAppsStateRepository {

    private val _rawAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override val rawAppsFlow: StateFlow<List<AppInfo>> = _rawAppsFlow

    @Volatile  // Nur das hier von paranoid
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()

    override fun updateApps(newApps: List<AppInfo>) {
        try {
            Timber.d("[DATAFLOW] 5. StateManager is being updated. Size: ${newApps.size}")

            if (newApps.isNotEmpty()) {
                lastSuccessfulAppList = newApps
            }

            _rawAppsFlow.value = newApps

        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Error updating apps in StateManager, keeping previous state")
        }
    }

    override fun getCurrentApps(): List<AppInfo> {
        return try {
            _rawAppsFlow.value.ifEmpty {
                Timber.d("Returning cached list with ${lastSuccessfulAppList.size} apps")
                lastSuccessfulAppList
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting current apps, returning cached list")
            lastSuccessfulAppList
        }
    }

    override fun purgeRepository() {
    }
}