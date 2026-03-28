package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Spezialisiertes Fake das Namen-Reaktivität simuliert.
 * Verwendet für CustomNamesViewModelTest.
 *
 * Nutzt intern FakeInstalledAppsRepository für den Flow.
 */
class ReactiveFakeInstalledAppsRepository(
    private val appNamesRepository: CustomNamesRepository
) : InstalledAppsRepository {

    private val delegate = FakeInstalledAppsRepository()

    private val rawApps = listOf(
        AppInfo("Clock", "Clock", "com.android.clock", "com.android.clock.Clock", true),
        AppInfo("Camera", "Camera", "com.android.camera", "com.android.camera.Camera", true),
        AppInfo("Calculator", "Calculator", "com.android.calculator", "com.android.calculator.Calculator", true)
    )

    /** Direkter Zugriff auf den Flow für Tests die ihn brauchen */
    val appsFlow get() = delegate.appsFlow

    override fun getInstalledApps(): Flow<List<AppInfo>> = delegate.getInstalledApps()

    override suspend fun triggerAppsUpdate() {
        val processedList = rawApps.map { app ->
            val displayName = appNamesRepository.getDisplayNameForPackage(
                app.packageName,
                app.originalName
            )
            app.copy(displayName = displayName)
        }.sortedBy { it.displayName.lowercase() }

        delegate.installedApps = processedList
    }

    override suspend fun purgeRepository() {
        delegate.purgeRepository()
    }
}