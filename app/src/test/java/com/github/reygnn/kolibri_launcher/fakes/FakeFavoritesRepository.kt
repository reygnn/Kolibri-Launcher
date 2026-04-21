package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-04 05:01

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFavoritesRepository : FavoritesRepository {
    val favoritesState = MutableStateFlow(setOf<String>())

    var favorites: Set<String>
        get() = favoritesState.value
        set(value) {
            favoritesState.value = value
        }

    override val favoriteComponentsFlow: Flow<Set<String>> = favoritesState

    override suspend fun isFavoriteComponent(componentName: String?) = componentName in favorites

    override suspend fun addFavoriteComponent(componentName: String): Boolean {
        if (componentName.isBlank()) return false
        favorites = favorites + componentName
        return true
    }

    override suspend fun removeFavoriteComponent(componentName: String): Boolean {
        if (componentName.isBlank()) return false
        favorites = favorites - componentName
        return true
    }

    override suspend fun toggleFavoriteComponent(componentName: String): Boolean {
        return if (componentName in favorites) {
            removeFavoriteComponent(componentName)
            false
        } else {
            addFavoriteComponent(componentName)
        }
    }

    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {
        favorites = favorites.intersect(installedComponentNames.toSet())
    }

    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        // Blank-Einträge filtern — konsistent mit addFavoriteComponent und
        // isFavoriteComponent, die beide Blanks ablehnen. Muss mit
        // FavoritesManager.saveFavoriteComponents synchron gehalten werden.
        favorites = componentNames.filter { it.isNotBlank() }.toSet()
    }

    override suspend fun purgeRepository() {
        favorites = emptySet()
    }
}