/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesManager : FavoritesRepository {

    private val dataStore: DataStore<Preferences>
    private val context: Context
    private val externalScope: CoroutineScope?
    override val favoriteComponentsFlow: Flow<Set<String>>

    private object PreferencesKeys {
        val FAVORITES = stringSetPreferencesKey("favorites_components_set")
    }

    /**
     * Primärer Konstruktor für Dagger/Hilt.
     */
    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        @ApplicationContext context: Context,
        @ApplicationScope externalScope: CoroutineScope?
    ) : this(
        dataStore = dataStore,
        context = context,
        externalScope = externalScope,
        sharingStrategy = SharingStarted.WhileSubscribed(5000L)
    )

    /**
     * Sekundärer, interner Konstruktor für Tests.
     */
    @VisibleForTesting
    internal constructor(
        dataStore: DataStore<Preferences>,
        context: Context,
        externalScope: CoroutineScope?,
        sharingStrategy: SharingStarted
    ) {
        this.dataStore = dataStore
        this.context = context
        this.externalScope = externalScope
        this.favoriteComponentsFlow = initializeFlow(sharingStrategy)
    }

    private fun initializeFlow(sharingStrategy: SharingStarted): Flow<Set<String>> {
        return dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading favorites preferences")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { preferences ->
                preferences[PreferencesKeys.FAVORITES] ?: emptySet()
            }
            .let { flow ->
                if (externalScope != null) {
                    flow.shareIn(
                        scope = externalScope,
                        started = sharingStrategy,
                        replay = 1
                    )
                } else {
                    flow
                }
            }
    }

    override suspend fun toggleFavoriteComponent(componentName: String): Boolean {
        return try {
            val isCurrentlyFavorite = isFavoriteComponent(componentName)
            if (isCurrentlyFavorite) {
                removeFavoriteComponent(componentName)
                false
            } else {
                addFavoriteComponent(componentName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error toggling favorite component: $componentName")
            false
        }
    }

    override suspend fun addFavoriteComponent(componentName: String): Boolean {
        if (componentName.isBlank()) return false

        return try {
            val currentFavorites = favoriteComponentsFlow.first()

            if (currentFavorites.contains(componentName)) {
                return true
            }

            val currentFavoritePackages = currentFavorites.map { it.split('/')[0] }.toSet()

            if (currentFavoritePackages.size >= AppConstants.MAX_FAVORITES_ON_HOME) {
                val newPackageName = componentName.split('/')[0]
                if (!currentFavoritePackages.contains(newPackageName)) {
                    Timber.w("Favorites limit reached. Cannot add component from new package: $componentName")
                    return false
                }
            }

            dataStore.edit { preferences ->
                val newFavorites = currentFavorites + componentName
                preferences[PreferencesKeys.FAVORITES] = newFavorites
            }
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding favorite component: $componentName")
            false
        }
    }

    override suspend fun removeFavoriteComponent(componentName: String): Boolean {
        if (componentName.isBlank()) return false

        return try {
            val currentFavorites = favoriteComponentsFlow.first()

            if (!currentFavorites.contains(componentName)) {
                return true
            }

            dataStore.edit { preferences ->
                val newFavorites = currentFavorites - componentName
                preferences[PreferencesKeys.FAVORITES] = newFavorites
            }
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error removing favorite component: $componentName")
            false
        }
    }

    override suspend fun isFavoriteComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            favoriteComponentsFlow.first().contains(componentName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error checking if component is favorite: $componentName")
            false
        }
    }

    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        try {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.FAVORITES] = componentNames.toSet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error saving favorite components")
        }
    }

    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {
        try {
            dataStore.edit { preferences ->
                val currentFavorites = preferences[PreferencesKeys.FAVORITES] ?: emptySet()
                if (currentFavorites.isEmpty()) return@edit

                val installedSet = installedComponentNames.toSet()
                val cleanedFavorites = currentFavorites.intersect(installedSet)

                if (cleanedFavorites.size < currentFavorites.size) {
                    val removedCount = currentFavorites.size - cleanedFavorites.size
                    Timber.w("Removed $removedCount invalid favorites")

                    if (BuildConfig.DEBUG) {
                        Timber.d("Backup favorites before cleanup: $currentFavorites")
                    }

                    preferences[PreferencesKeys.FAVORITES] = cleanedFavorites
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to cleanup favorites, keeping current state")
            // Nicht crashen, einfach den aktuellen Zustand behalten
        }
    }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}