package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Produces the list of favorite apps shown on the home screen, with a
 * fallback to top-N alphabetically-sorted apps when the user has set
 * no favorites.
 *
 * == ARCHITECTURE RULE: hidden apps and the home screen ==
 * (First written-down location of this rule. Kept in this KDoc as the
 * canonical reference; if the rule ever moves to a higher-level doc,
 * this block can be replaced with a link.)
 *
 * Hidden apps applies only to the AppDrawer. On the home screen, the
 * hidden filter is broken only by favorite status — a favorite that is
 * also hidden remains pinned to the home screen, because favorites
 * must always be visible.
 *
 * The two code paths in this use case follow from that one rule:
 *
 *   - Favorites path ([processApps] when favorites exist, Z. 68–84):
 *     filter by `isFavorite` only. Hidden flag does not apply, because
 *     the favorite-status break is in effect.
 *
 *   - Fallback path ([createFallbackApps], Z. 155–168, used when the
 *     user has set no favorites): filter by `!hidden`. There is no
 *     favorite status to break the hidden filter, so the filter
 *     applies as it does in the drawer.
 *
 * The unhide path remains reachable for a hidden favorite via long-press
 * on the home screen entry; HomeFragment routes the resulting UnhideApp
 * action to onShowApp. The same action is unreachable from the
 * AppDrawer for the symmetric reason — hidden apps do not appear in
 * the drawer listing (see [GetDrawerAppsUseCase] Z. 56), so they
 * cannot be long-pressed there.
 *
 * == Why HiddenAppsRepository is injected ==
 * The flow is part of the combined state graph because both paths need
 * it: the favorites path needs to know the hidden set is in scope (for
 * the rule above), and the fallback path uses it as a filter directly.
 */
class GetFavoriteAppsUseCase @Inject constructor(
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val favoritesRepository: FavoritesRepository,
    private val favoritesOrderRepository: FavoritesOrderRepository,
    private val hiddenAppsRepository: HiddenAppsRepository
) {

    val favoriteApps: Flow<UiState<FavoriteAppsResult>> = combine(
        installedAppsStateRepository.rawAppsFlow,
        favoritesRepository.favoriteComponentsFlow.catch { e ->
            Timber.w(e, "favoriteComponentsFlow error - using empty set fallback")
            emit(emptySet())
        },
        hiddenAppsRepository.hiddenAppsFlow.catch { e ->
            Timber.w(e, "hiddenAppsFlow error - showing all apps")
            emit(emptySet())
        },
        favoritesOrderRepository.favoriteComponentsOrderFlow.catch { e ->
            Timber.w(e, "favoriteComponentsOrderFlow error - using empty order")
            emit(emptyList())
        }
    ) { rawApps, favorites, hiddenApps, savedOrder ->
        Timber.d("[DATAFLOW-FAV] Combine triggered - rawApps: ${rawApps.size}, favorites: ${favorites.size}")

        // Leere App-Liste → Loading state
        if (rawApps.isEmpty()) {
            return@combine UiState.Loading
        }

        processApps(rawApps, favorites, hiddenApps, savedOrder)
    }.catch { e ->
        TimberWrapper.silentError(e, "Critical error in favoriteApps flow")
        emit(UiState.Error("Failed to load apps"))
    }

    private suspend fun processApps(
        rawApps: List<AppInfo>,
        favorites: Set<String>,
        // Two uses, both consistent with the architecture rule (see
        // class KDoc): not used as a filter on favorites in this body
        // (the favorite-status break is in effect), but forwarded to
        // createFallbackApps where it does filter (no favorites set
        // means no break, so the hidden filter applies normally).
        // Do NOT add a hidden filter to the favorites filter on
        // line 91 below without revisiting the architecture rule.
        hiddenApps: Set<String>,
        savedOrder: List<String>
    ): UiState<FavoriteAppsResult> {
        // Markiere Favoriten-Status — Set.contains(String), .map, .filter
        // auf Non-Null-Datenklassen können nicht werfen.
        val appsWithFavoriteStatus = rawApps.map { app ->
            app.copy(isFavorite = favorites.contains(app.componentName))
        }
        val favoriteApps = appsWithFavoriteStatus.filter { it.isFavorite }

        // Einziger Wurfkandidat: sortFavoriteComponents (suspend, Repo-Call).
        val orderedFavorites = try {
            favoritesOrderRepository.sortFavoriteComponents(favoriteApps, savedOrder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "Sorting failed - using alphabetical fallback")
            favoriteApps.sortedBy { it.displayName.lowercase() }
        }

        val limitedOrderedFavorites = orderedFavorites.take(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)

        return if (limitedOrderedFavorites.isNotEmpty()) {
            Timber.d("[DATAFLOW-FAV] Emitting ${limitedOrderedFavorites.size} favorites")
            UiState.Success(
                FavoriteAppsResult(
                    apps = limitedOrderedFavorites,
                    isFallback = false
                )
            )
        } else {
            // Fallback: Top N sichtbare Apps
            val fallbackApps = createFallbackApps(rawApps, hiddenApps)
            Timber.d("[DATAFLOW-FAV] No favorites - emitting ${fallbackApps.size} fallback apps")
            UiState.Success(
                FavoriteAppsResult(
                    apps = fallbackApps,
                    isFallback = true
                )
            )
        }
        // Programmierfehler-Pfad: nicht mehr inline; propagiert zum
        // Flow-catch oben (Z. 88), der UiState.Error("Failed to load
        // apps") emittiert.
    }

    private fun createFallbackApps(
        rawApps: List<AppInfo>,
        hiddenApps: Set<String>
    ): List<AppInfo> {
        // Filter / sortedBy / take auf String-Properties — kann nicht werfen.
        return rawApps
            .filter { !hiddenApps.contains(it.componentName) }
            .sortedBy { it.displayName.lowercase() }
            .take(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
    }

    suspend fun purgeRepository() {
        // Für Tests
    }
}