package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow

interface AppVisibilityRepository : Purgeable {
    val hiddenAppsFlow: Flow<Set<String>>   // Dieser Flow liefert componentNames

    suspend fun isComponentHidden(componentName: String?): Boolean
    suspend fun hideComponent(componentName: String?): Boolean
    suspend fun showComponent(componentName: String?): Boolean
    suspend fun updateComponentVisibilities(componentsToHide: Set<String>, componentsToShow: Set<String>)
}