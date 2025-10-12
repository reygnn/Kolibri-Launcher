/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetOnboardingAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository
) : GetOnboardingAppsUseCaseRepository {

    override val onboardingAppsFlow: Flow<List<AppInfo>> =
        installedAppsRepository.getInstalledApps()
            .map { apps ->
                try {
                    // Validierung: Entferne potentiell defekte Apps
                    apps.filter { app ->
                        try {
                            app.packageName.isNotBlank() &&
                                    app.displayName.isNotBlank()
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error validating app for onboarding")
                            false
                        }
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error filtering onboarding apps, returning all")
                    apps
                }
            }
            .catch { e ->
                TimberWrapper.silentError(e, "Error in onboarding apps flow, emitting empty list")
                emit(emptyList())
            }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}