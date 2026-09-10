package com.github.reygnn.kolibri_launcher.domain.repository

import kotlinx.coroutines.flow.Flow

interface HiddenAppsRepository : Purgeable {
    val hiddenAppsFlow: Flow<Set<String>>   // Dieser Flow liefert componentNames

    suspend fun isComponentHidden(componentName: String?): Boolean
    suspend fun hideComponent(componentName: String?): Boolean
    suspend fun showComponent(componentName: String?): Boolean
    suspend fun updateComponentVisibilities(componentsToHide: Set<String>, componentsToShow: Set<String>)

    /**
     * Reconciles the hidden set against the loaded app list, gating each
     * removal through [isStillPresent] — analogous to
     * [FavoritesRepository.reconcileFavoriteComponents] (RECONCILE_FIX_SPEC
     * R-INV-2). A hidden component absent from [installedComponentNames] is only
     * a candidate; it is removed only if [isStillPresent] returns false. The
     * candidate read and the delete are the same fail-closed store read; the
     * empty-installed guard lives at the caller.
     */
    suspend fun reconcileHiddenComponents(
        installedComponentNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    )
}