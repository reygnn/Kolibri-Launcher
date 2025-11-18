package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetFavoriteComponentsUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository
) {
    /**
     * Ruft die aktuellen favorisierten Komponenten-Namen einmalig ab.
     *
     * @throws Exception wenn das Laden fehlschlägt
     */
    suspend operator fun invoke(): Set<String> {
        return favoritesRepository.favoriteComponentsFlow.first()
    }
}