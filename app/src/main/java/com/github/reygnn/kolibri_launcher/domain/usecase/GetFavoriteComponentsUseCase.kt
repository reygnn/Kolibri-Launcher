package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetFavoriteComponentsUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository
) {
    /**
     * Ruft die aktuellen favorisierten Komponenten-Namen einmalig ab.
     */
    suspend operator fun invoke(): Set<String> {
        return try {
            favoritesRepository.favoriteComponentsFlow.first()
        } catch (e: Exception) {
            emptySet() // Sicherer Fallback
        }
    }
}