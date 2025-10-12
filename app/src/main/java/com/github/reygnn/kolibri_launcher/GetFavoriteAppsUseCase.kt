/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetFavoriteAppsUseCase @Inject constructor(
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    private val appVisibilityManager: AppVisibilityRepository
) : GetFavoriteAppsUseCaseRepository {

    override val favoriteApps: Flow<UiState<FavoriteAppsResult>> = flow {
        // Emit Loading initial state
        emit(UiState.Loading)

        combine(
            // Critical Flow: Crash sollte zu Error führen
            installedAppsStateRepository.rawAppsFlow,

            // Non-critical Flows: Crash → Fallback-Wert
            favoritesManager.favoriteComponentsFlow.catch { e ->
                Timber.w(e, "favoriteComponentsFlow error - using empty set fallback")
                emit(emptySet())
            },
            appVisibilityManager.hiddenAppsFlow.catch { e ->
                Timber.w(e, "hiddenAppsFlow error - showing all apps")
                emit(emptySet())
            },
            favoritesOrderManager.favoriteComponentsOrderFlow.catch { e ->
                Timber.w(e, "favoriteComponentsOrderFlow error - using empty order")
                emit(emptyList())
            }
        ) { rawApps, favorites, hiddenApps, savedOrder ->
            CombineResult(rawApps, favorites, hiddenApps, savedOrder)
        }
            .collect { result ->
                Timber.d("[DATAFLOW-FAV] Combine triggered - rawApps: ${result.rawApps.size}, favorites: ${result.favorites.size}")

                // Leere App-Liste → skip emission
                if (result.rawApps.isEmpty()) {
                    return@collect
                }

                val state = processApps(
                    result.rawApps,
                    result.favorites,
                    result.hiddenApps,
                    result.savedOrder
                )

                emit(state)
            }
    }.catch { e ->
        // Nur critical Errors landen hier (rawAppsFlow)
        Timber.e(e, "Critical error in favoriteApps flow")
        emit(UiState.Error("Failed to load apps"))
    }

    private data class CombineResult(
        val rawApps: List<AppInfo>,
        val favorites: Set<String>,
        val hiddenApps: Set<String>,
        val savedOrder: List<String>
    )

    private suspend fun processApps(
        rawApps: List<AppInfo>,
        favorites: Set<String>,
        hiddenApps: Set<String>,
        savedOrder: List<String>
    ): UiState<FavoriteAppsResult> {
        return try {
            // Markiere Favoriten-Status
            val appsWithFavoriteStatus = rawApps.map { app ->
                app.copy(isFavorite = favorites.contains(app.componentName))
            }

            // Filter nur Favoriten
            val favoriteApps = appsWithFavoriteStatus.filter { it.isFavorite }

            // Sortiere nach gespeicherter Reihenfolge (suspend function!)
            val orderedFavorites = try {
                favoritesOrderManager.sortFavoriteComponents(favoriteApps, savedOrder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Sorting failed - using alphabetical fallback")
                favoriteApps.sortedBy { it.displayName.lowercase() }
            }

            // Wenn Favoriten vorhanden: Diese verwenden
            if (orderedFavorites.isNotEmpty()) {
                Timber.d("[DATAFLOW-FAV] Emitting ${orderedFavorites.size} favorites")
                UiState.Success(FavoriteAppsResult(
                    apps = orderedFavorites,
                    isFallback = false
                ))
            } else {
                // Fallback: Top N sichtbare Apps
                val fallbackApps = createFallbackApps(rawApps, hiddenApps)
                Timber.d("[DATAFLOW-FAV] No favorites - emitting ${fallbackApps.size} fallback apps")
                UiState.Success(FavoriteAppsResult(
                    apps = fallbackApps,
                    isFallback = true
                ))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unerwarteter Fehler in der Verarbeitung
            Timber.e(e, "Error processing apps - returning fallback")
            val fallbackApps = createFallbackApps(rawApps, hiddenApps)
            UiState.Success(FavoriteAppsResult(
                apps = fallbackApps,
                isFallback = true
            ))
        }
    }

    private fun createFallbackApps(
        rawApps: List<AppInfo>,
        hiddenApps: Set<String>
    ): List<AppInfo> {
        return try {
            rawApps
                .filter { !hiddenApps.contains(it.componentName) }
                .sortedBy { it.displayName.lowercase() }
                .take(AppConstants.MAX_FAVORITES_ON_HOME)
        } catch (e: Exception) {
            Timber.e(e, "Error creating fallback - using first ${AppConstants.MAX_FAVORITES_ON_HOME} apps")
            rawApps.take(AppConstants.MAX_FAVORITES_ON_HOME)
        }
    }

    override fun purgeRepository() {
        // Für Tests
    }
}