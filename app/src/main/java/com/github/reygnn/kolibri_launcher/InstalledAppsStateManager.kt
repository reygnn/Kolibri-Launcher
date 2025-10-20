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