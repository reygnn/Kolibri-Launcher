package com.github.reygnn.kolibri_launcher.domain.repository

import kotlinx.coroutines.flow.Flow

interface FavoritesRepository : Purgeable {
    val favoriteComponentsFlow: Flow<Set<String>>

    suspend fun isFavoriteComponent(componentName: String?): Boolean

    /**
     * Reconciles favorites against the freshly loaded app list, gating every
     * deletion through [isStillPresent] (RECONCILE_FIX_SPEC R-INV-2). A favorite
     * absent from [installedComponentNames] is only a removal CANDIDATE; it is
     * removed only if [isStillPresent] returns false for it. The candidate read
     * and the delete are the SAME store read (fail-closed: a read error
     * propagates, deleting nothing), so a partial/transient load cannot prune a
     * still-installed favorite. [isStillPresent] runs off the edit transaction;
     * the delete re-reads inside `edit{}` and removes value-scoped.
     */
    suspend fun reconcileFavoriteComponents(
        installedComponentNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    )
    suspend fun toggleFavoriteComponent(componentName: String): Boolean
    suspend fun addFavoriteComponent(componentName: String): Boolean
    suspend fun removeFavoriteComponent(componentName: String): Boolean
    suspend fun saveFavoriteComponents(componentNames: List<String>)
}