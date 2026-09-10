package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.AppLoadResult

/**
 * Enumerates the launchable apps (for the drawer + reconcile). Returns an
 * [AppLoadResult] so a failed enumeration stays a failure (RHL-INV-1) instead of
 * masquerading as an empty device. A thin system wrapper — verified by an
 * androidTest against the platform, not a JVM contract.
 */
interface InstalledAppsRepository {
    suspend fun loadInstalledApps(): AppLoadResult
}
