package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetFavoriteAppsUseCase @Inject constructor(
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    private val appVisibilityManager: HiddenAppsRepository
) {

    val favoriteApps: Flow<UiState<FavoriteAppsResult>> = combine(
        installedAppsStateRepository.rawAppsFlow,
        favoritesManager.favoriteComponentsFlow.catch { e ->
            Timber.Forest.w(e, "favoriteComponentsFlow error - using empty set fallback")
            emit(emptySet())
        },
        appVisibilityManager.hiddenAppsFlow.catch { e ->
            Timber.Forest.w(e, "hiddenAppsFlow error - showing all apps")
            emit(emptySet())
        },
        favoritesOrderManager.favoriteComponentsOrderFlow.catch { e ->
            Timber.Forest.w(e, "favoriteComponentsOrderFlow error - using empty order")
            emit(emptyList())
        }
    ) { rawApps, favorites, hiddenApps, savedOrder ->
        Timber.Forest.d("[DATAFLOW-FAV] Combine triggered - rawApps: ${rawApps.size}, favorites: ${favorites.size}")

        // Leere App-Liste → Loading state
        if (rawApps.isEmpty()) {
            return@combine UiState.Loading
        }

        processApps(rawApps, favorites, hiddenApps, savedOrder)
    }.catch { e ->
        Timber.Forest.e(e, "Critical error in favoriteApps flow")
        emit(UiState.Error("Failed to load apps"))
    }

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
            } catch (e: Throwable) {
                Timber.Forest.w(e, "Sorting failed - using alphabetical fallback")
                favoriteApps.sortedBy { it.displayName.lowercase() }
            }

            // Limitiere auf MAX_FAVORITES_ON_HOME
            val limitedOrderedFavorites = orderedFavorites.take(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)

            // Wenn Favoriten vorhanden: Diese verwenden
            if (limitedOrderedFavorites.isNotEmpty()) {
                Timber.Forest.d("[DATAFLOW-FAV] Emitting ${limitedOrderedFavorites.size} favorites")
                UiState.Success(
                    FavoriteAppsResult(
                        apps = limitedOrderedFavorites,
                        isFallback = false
                    )
                )
            } else {
                // Fallback: Top N sichtbare Apps
                val fallbackApps = createFallbackApps(rawApps, hiddenApps)
                Timber.Forest.d("[DATAFLOW-FAV] No favorites - emitting ${fallbackApps.size} fallback apps")
                UiState.Success(
                    FavoriteAppsResult(
                        apps = fallbackApps,
                        isFallback = true
                    )
                )
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Unerwarteter Fehler in der Verarbeitung
            Timber.Forest.e(e, "Error processing apps - returning fallback")
            val fallbackApps = createFallbackApps(rawApps, hiddenApps)
            UiState.Success(
                FavoriteAppsResult(
                    apps = fallbackApps,
                    isFallback = true
                )
            )
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
                .take(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
        } catch (e: Throwable) {
            Timber.Forest.e(e, "Error creating fallback - using first ${AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME} apps")
            rawApps.take(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
        }
    }

    suspend fun purgeRepository() {
        // Für Tests
    }
}