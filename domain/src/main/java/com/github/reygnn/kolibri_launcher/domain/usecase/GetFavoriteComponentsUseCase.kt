package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import javax.inject.Inject

class GetFavoriteComponentsUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository
) {
    /**
     * Ruft die aktuellen favorisierten Komponenten-Namen einmalig ab.
     *
     * Authoritative FRESH read via [FavoritesRepository.getFavoriteComponentsSnapshot]:
     * bypasses the hot-shared `favoriteComponentsFlow` replay cache. Sole caller is the
     * Onboarding EDIT_FAVORITES pre-selection, which runs in a SEPARATE Activity with no
     * warm Home subscriber — a `.first()` on the replay flow could return a stale set
     * (e.g. after a backup restore). Same fix as the swipe-action fresh reads.
     *
     * @throws Exception wenn das Laden fehlschlägt
     */
    suspend operator fun invoke(): Set<String> {
        return favoritesRepository.getFavoriteComponentsSnapshot()
    }
}