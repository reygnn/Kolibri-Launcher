package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow

interface FavoritesRepository : Purgeable {
    val favoriteComponentsFlow: Flow<Set<String>>

    suspend fun isFavoriteComponent(componentName: String?): Boolean

    suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>)

    suspend fun toggleFavoriteComponent(componentName: String): Boolean

    suspend fun addFavoriteComponent(componentName: String): Boolean

    suspend fun removeFavoriteComponent(componentName: String): Boolean

    suspend fun saveFavoriteComponents(componentNames: List<String>)
}