package com.github.reygnn.kolibri_launcher.fakes

// 2025-12-04 20:11

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHiddenAppsRepository : HiddenAppsRepository {
    val hiddenAppsState = MutableStateFlow(setOf<String>())

    var hiddenApps: Set<String>
        get() = hiddenAppsState.value
        set(value) {
            hiddenAppsState.value = value
        }

    override val hiddenAppsFlow: Flow<Set<String>> = hiddenAppsState

    override suspend fun isComponentHidden(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false
        return componentName in hiddenApps
    }

    override suspend fun hideComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false
        hiddenApps = hiddenApps + componentName
        return true
    }

    override suspend fun showComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false
        hiddenApps = hiddenApps - componentName
        return true
    }

    override suspend fun updateComponentVisibilities(
        componentsToHide: Set<String>,
        componentsToShow: Set<String>
    ) {
        hiddenApps = (hiddenApps + componentsToHide) - componentsToShow
    }

    override suspend fun purgeRepository() {
        hiddenApps = emptySet()
    }
}