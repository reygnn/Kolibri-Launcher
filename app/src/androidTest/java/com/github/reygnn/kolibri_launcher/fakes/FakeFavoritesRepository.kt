package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFavoritesRepository : FavoritesRepository, Purgeable {
    val favoritesState: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val favoriteComponentsFlow: Flow<Set<String>> = favoritesState
    val favorites: Set<String> get() = favoritesState.value
    override suspend fun isFavoriteComponent(componentName: String?): Boolean =
        componentName != null && favoritesState.value.contains(componentName)

    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {
        favoritesState.value = favoritesState.value.intersect(installedComponentNames.toSet())
    }

    override suspend fun toggleFavoriteComponent(componentName: String): Boolean {
        val isFavorite = favoritesState.value.contains(componentName); if (isFavorite) {
            removeFavoriteComponent(componentName)
        } else {
            addFavoriteComponent(componentName)
        }; return !isFavorite
    }

    override suspend fun addFavoriteComponent(componentName: String): Boolean {
        favoritesState.value = favoritesState.value + componentName; return true
    }

    override suspend fun removeFavoriteComponent(componentName: String): Boolean {
        favoritesState.value = favoritesState.value - componentName; return true
    }

    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        favoritesState.value = componentNames.toSet()
    }

    override suspend fun purgeRepository() {
        favoritesState.value = emptySet()
    }
}