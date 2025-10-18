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

@Singleton
class InstalledAppsStateManager @Inject constructor() : InstalledAppsStateRepository {

    private val _rawAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override val rawAppsFlow: StateFlow<List<AppInfo>> = _rawAppsFlow

    // Cache für Recovery-Mechanismus
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()

    override fun updateApps(newApps: List<AppInfo>) {
        try {
            Timber.d("[DATAFLOW] 5. StateManager is being updated. Size: ${newApps.size}")

            // Nur nicht-leere Listen als "erfolgreich" cachen
            if (newApps.isNotEmpty()) {
                lastSuccessfulAppList = newApps
            }

            _rawAppsFlow.value = newApps

        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating apps in StateManager, keeping previous state")
            // Behalte den alten State bei - _rawAppsFlow.value wird nicht verändert
        }
    }

    override fun getCurrentApps(): List<AppInfo> {
        return try {
            val currentApps = _rawAppsFlow.value

            if (currentApps.isNotEmpty()) {
                currentApps
            } else {
                // Fallback auf die letzte erfolgreiche Liste
                Timber.d("Current app list is empty, returning cached list with ${lastSuccessfulAppList.size} apps")
                lastSuccessfulAppList
            }

        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error getting current apps, returning cached list")
            // Im absoluten Worst-Case: Gib die gecachte Liste zurück
            lastSuccessfulAppList
        }
    }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
        // In-Memory State wird automatisch beim App-Neustart gelöscht
    }
}