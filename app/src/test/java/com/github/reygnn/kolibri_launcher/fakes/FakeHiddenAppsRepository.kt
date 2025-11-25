package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHiddenAppsRepository : HiddenAppsRepository {
    private val flow = MutableStateFlow(setOf<String>())

    var hiddenApps: Set<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val hiddenAppsFlow = flow

    override suspend fun isComponentHidden(componentName: String?) = componentName in hiddenApps
    override suspend fun hideComponent(componentName: String?) = true
    override suspend fun showComponent(componentName: String?) = true
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