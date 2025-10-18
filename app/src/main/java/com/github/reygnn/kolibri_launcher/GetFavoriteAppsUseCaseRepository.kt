package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow

/**
 * Der Vertrag (Interface) für den Use Case, der die Favoriten-Apps bereitstellt.
 */
interface GetFavoriteAppsUseCaseRepository : Purgeable {
    val favoriteApps: Flow<UiState<FavoriteAppsResult>>
}