package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ToggleFavoriteUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository // <-- Injiziert das Repo-Interface
) {
    /**
     * Definiert das Ergebnis der Umschalt-Aktion, damit das ViewModel weiß,
     * welchen Toast es anzeigen soll.
     */
    sealed class Result {
        data class Success(val messageResId: Int) : Result()
        data class Error(val messageResId: Int) : Result()
    }

    /**
     * Führt die Umschalt-Logik aus.
     * @param app Die App, die umgeschaltet wird.
     * @param currentMaxFavorites Das aktuelle UI-Limit (wird vom VM übergeben).
     */
    suspend operator fun invoke(app: AppInfo, currentMaxFavorites: Int): Result {
        val realFavoritesCount = favoritesRepository.favoriteComponentsFlow.first().size

        if (!favoritesRepository.isFavoriteComponent(app.componentName) &&
            realFavoritesCount >= currentMaxFavorites
        ) {
            return Result.Error(R.string.favorites_limit_reached)
        }

        val wasAdded = favoritesRepository.toggleFavoriteComponent(app.componentName)

        val messageResId = if (wasAdded) {
            R.string.app_added_to_favorites
        } else {
            R.string.app_removed_from_favorites
        }
        return Result.Success(messageResId)
    }
}