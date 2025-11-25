package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.repository.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeGetFavoriteAppsUseCaseRepository : GetFavoriteAppsUseCaseRepository, Purgeable {
    val favoriteAppsState: MutableStateFlow<UiState<FavoriteAppsResult>> =
        MutableStateFlow(UiState.Loading)
    override val favoriteApps: Flow<UiState<FavoriteAppsResult>> = favoriteAppsState

    // Speichert das Limit, das vom ViewModel (im Test) gesetzt wurde
    var currentDynamicMax: Int = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
        private set

    /**
     * NEU: Implementierung der Interface-Methode.
     * Im Test kannst du 'currentDynamicMax' prüfen, um zu sehen,
     * ob der richtige Wert vom ViewModel gesendet wurde.
     */
    override fun setDynamicMaxFavorites(max: Int) {
        currentDynamicMax = max
        // Optional: Du könntest hier auch Logik einfügen, um
        // 'favoriteAppsState' basierend auf dem Limit neu auszugeben,
        // aber für die meisten Tests reicht es, den Wert zu speichern.
    }

    override suspend fun purgeRepository() {
        favoriteAppsState.value = UiState.Loading
        currentDynamicMax = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
    }

    // Hilfsfunktion für deine Tests, um den State einfach zu setzen
    fun emitSuccess(apps: List<AppInfo>) {
        favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(apps = apps, isFallback = false)
        )
    }

    fun emitLoading() {
        favoriteAppsState.value = UiState.Loading
    }
}