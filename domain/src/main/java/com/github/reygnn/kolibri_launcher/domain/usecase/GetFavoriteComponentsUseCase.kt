package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import javax.inject.Inject

class GetFavoriteComponentsUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository
) {
    /**
     * Reads the current favorites for the Onboarding EDIT_FAVORITES pre-selection
     * as a DISTINGUISHABLE result (DATASTORE_READ_SPEC Belang C): [FavoritesEditRead.Loaded]
     * on success, [FavoritesEditRead.Unavailable] on an I/O failure — never an empty
     * set masquerading as "no favorites". Fail-CLOSED, because the pre-selection feeds
     * a subsequent SAVE and an unreadable store must not let the editor wipe the real
     * favorites (DSR-INV-4). Delegates to
     * [FavoritesRepository.readFavoritesForEdit]; a non-I/O programmer error still
     * propagates, cancellation always propagates.
     */
    suspend operator fun invoke(): FavoritesEditRead {
        return favoritesRepository.readFavoritesForEdit()
    }
}
