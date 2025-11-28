package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import kotlinx.coroutines.flow.Flow

/**
 * Der Vertrag (Interface) für den Use Case, der die Favoriten-Apps bereitstellt.
 */
interface GetFavoriteAppsUseCaseRepository : Purgeable {
    val favoriteApps: Flow<UiState<FavoriteAppsResult>>
    fun setDynamicMaxFavorites(max: Int)
}