package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFavoritesRepository : FavoritesRepository {
    private val flow = MutableStateFlow(setOf<String>())

    var favorites: Set<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val favoriteComponentsFlow = flow

    override suspend fun isFavoriteComponent(componentName: String?) = componentName in favorites
    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {}
    override suspend fun toggleFavoriteComponent(componentName: String) = true
    override suspend fun addFavoriteComponent(componentName: String) = true
    override suspend fun removeFavoriteComponent(componentName: String) = true
    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        favorites = componentNames.toSet()
    }

    override suspend fun purgeRepository() {
        favorites = emptySet()
    }
}