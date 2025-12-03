package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-03 19:13

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
        favorites = componentNames.toSet()
    }

    override suspend fun purgeRepository() {
        favorites = emptySet()
    }
}