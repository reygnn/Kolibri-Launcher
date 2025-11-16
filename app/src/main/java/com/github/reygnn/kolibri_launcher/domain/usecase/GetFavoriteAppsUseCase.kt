package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    // NEU: Interner StateFlow für das dynamische Limit.
    // Startet mit dem Standard-Fallback-Wert aus AppConstants.
    private val dynamicMaxFavorites = MutableStateFlow(AppConstants.MAX_FAVORITES_ON_HOME)

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
        },
        dynamicMaxFavorites // <-- NEU: Das dynamische Limit einbeziehen
    ) { rawApps, favorites, hiddenApps, savedOrder, maxFavoritesToShow -> // <-- NEUER PARAMETER
        Timber.Forest.d("[DATAFLOW-FAV] Combine triggered - rawApps: ${rawApps.size}, favorites: ${favorites.size}, max: $maxFavoritesToShow")

        // Leere App-Liste → Loading state
        if (rawApps.isEmpty()) {
            return@combine UiState.Loading
        }

        // NEU: 'maxFavoritesToShow' an processApps übergeben
        processApps(rawApps, favorites, hiddenApps, savedOrder, maxFavoritesToShow)
    }.catch { e ->
        Timber.Forest.e(e, "Critical error in favoriteApps flow")
        emit(UiState.Error("Failed to load apps"))
    }

    private suspend fun processApps(
        rawApps: List<AppInfo>,
        favorites: Set<String>,
        hiddenApps: Set<String>,
        savedOrder: List<String>,
        maxFavoritesToShow: Int
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

            // NEU: Wende das dynamische Limit auf die ECHTEN Favoriten an
            val limitedOrderedFavorites = orderedFavorites.take(maxFavoritesToShow)


            // Wenn Favoriten vorhanden: Diese verwenden
            if (limitedOrderedFavorites.isNotEmpty()) {
                Timber.Forest.d("[DATAFLOW-FAV] Emitting ${limitedOrderedFavorites.size} favorites (Limit: $maxFavoritesToShow)")
                UiState.Success(
                    FavoriteAppsResult(
                        apps = limitedOrderedFavorites, // <-- BENUTZE DIE LIMITIERTE LISTE
                        isFallback = false
                    )
                )
            } else {
                // Fallback: Top N sichtbare Apps
                // NEU: 'maxFavoritesToShow' an Fallback übergeben
                val fallbackApps = createFallbackApps(rawApps, hiddenApps, maxFavoritesToShow)
                Timber.Forest.d("[DATAFLOW-FAV] No favorites - emitting ${fallbackApps.size} fallback apps (Limit: $maxFavoritesToShow)")
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
            // NEU: 'maxFavoritesToShow' an Fallback übergeben
            val fallbackApps = createFallbackApps(rawApps, hiddenApps, maxFavoritesToShow)
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
        hiddenApps: Set<String>,
        maxFavoritesToShow: Int // <-- NEUER PARAMETER
    ): List<AppInfo> {
        return try {
            rawApps
                .filter { !hiddenApps.contains(it.componentName) }
                .sortedBy { it.displayName.lowercase() }
                .take(maxFavoritesToShow) // <-- BENUTZE DEN DYNAMISCHEN WERT
        } catch (e: Throwable) {
            Timber.Forest.e(e, "Error creating fallback - using first $maxFavoritesToShow apps")
            rawApps.take(maxFavoritesToShow) // <-- BENUTZE DEN DYNAMISCHEN WERT
        }
    }

    /**
     * NEU: Implementierung der Interface-Methode.
     * Wird vom HomeViewModel aufgerufen.
     */
    fun setDynamicMaxFavorites(max: Int) {
        if (max > 0 && max != dynamicMaxFavorites.value) {
            Timber.Forest.i("Setting dynamic max favorites to: $max")
            dynamicMaxFavorites.value = max
        }
    }

    suspend fun purgeRepository() {
        // Für Tests: Setze das Limit zurück
        dynamicMaxFavorites.value = AppConstants.MAX_FAVORITES_ON_HOME
    }
}