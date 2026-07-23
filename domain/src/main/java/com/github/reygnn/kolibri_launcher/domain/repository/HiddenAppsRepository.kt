package com.github.reygnn.kolibri_launcher.domain.repository

import kotlinx.coroutines.flow.Flow

interface HiddenAppsRepository : Purgeable {
    val hiddenAppsFlow: Flow<Set<String>>   // Dieser Flow liefert componentNames

    suspend fun isComponentHidden(componentName: String?): Boolean
    suspend fun hideComponent(componentName: String?): Boolean
    suspend fun showComponent(componentName: String?): Boolean
    suspend fun updateComponentVisibilities(componentsToHide: Set<String>, componentsToShow: Set<String>)

    /**
     * Removes any hidden componentName that is not in
     * [installedComponentNames] (app uninstalled). Called after every
     * successful app load from
     * [com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase],
     * analogous to [FavoritesRepository.cleanupFavoriteComponents]. The
     * empty-installed guard lives at the caller (guard on an empty app list),
     * so a cold start does not wipe the hidden set.
     */
    suspend fun cleanupHiddenComponents(installedComponentNames: List<String>)
}