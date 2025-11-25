package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class ReactiveFakeInstalledAppsRepository(
    private val appNamesRepository: CustomNamesRepository
) : InstalledAppsRepository {

    private val rawApps = listOf(
        AppInfo("Clock", "Clock", "com.android.clock", "com.android.clock.Clock", true),
        AppInfo("Camera", "Camera", "com.android.camera", "com.android.camera.Camera", true),
        AppInfo(
            "Calculator",
            "Calculator",
            "com.android.calculator",
            "com.android.calculator.Calculator",
            true
        )
    )
    private val appFlow = MutableStateFlow<List<AppInfo>>(emptyList())

    override fun getInstalledApps(): Flow<List<AppInfo>> {
        return appFlow
    }

    override suspend fun triggerAppsUpdate() {
        val processedList = rawApps.map { app ->
            val displayName = appNamesRepository.getDisplayNameForPackage(app.packageName, app.originalName)
            app.copy(displayName = displayName)
        }.sortedBy { it.displayName.lowercase() }

        appFlow.value = processedList
    }

    override suspend fun purgeRepository() {
        appFlow.value = emptyList()
    }
}