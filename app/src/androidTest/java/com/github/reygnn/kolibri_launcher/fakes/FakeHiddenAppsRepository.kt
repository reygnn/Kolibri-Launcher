package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeHiddenAppsRepository : HiddenAppsRepository, Purgeable {
    val hiddenAppsState: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val hiddenAppsFlow: Flow<Set<String>> = hiddenAppsState
    val hiddenApps: Set<String> get() = hiddenAppsState.value
    override suspend fun isComponentHidden(componentName: String?): Boolean =
        componentName != null && hiddenAppsState.value.contains(componentName)

    override suspend fun hideComponent(componentName: String?): Boolean {
        if (componentName != null) hiddenAppsState.value =
            hiddenAppsState.value + componentName; return true
    }

    override suspend fun showComponent(componentName: String?): Boolean {
        if (componentName != null) hiddenAppsState.value =
            hiddenAppsState.value - componentName; return true
    }

    override suspend fun updateComponentVisibilities(
        componentsToHide: Set<String>,
        componentsToShow: Set<String>
    ) {
        hiddenAppsState.update { currentHidden ->
            val newHidden = currentHidden.toMutableSet()
            newHidden.addAll(componentsToHide)
            newHidden.removeAll(componentsToShow)
            newHidden.toSet()
        }
    }

    override suspend fun purgeRepository() {
        hiddenAppsState.value = emptySet()
    }
}