package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ToggleFavoriteUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository
) {
    /**
     * Definiert das Ergebnis der Umschalt-Aktion, damit das ViewModel weiß,
     * welchen Toast es anzeigen soll.
     *
     * UI-Layer maps these sealed identifiers to `R.string.*` resources via
     * `mapToStringResId(...)`. Keeping them as a sealed type instead of
     * `@StringRes Int` lets the domain stay free of `androidx.annotation`
     * and Android resource ids.
     */
    sealed class Result {
        sealed class Success : Result() {
            /** Favorite was added — UI shows toast with the app's display name. */
            object Added : Success()

            /** Favorite was removed — UI shows toast with the app's display name. */
            object Removed : Success()
        }

        sealed class Error : Result() {
            /**
             * The user tried to add a favorite but the configured limit is
             * already exhausted. UI shows toast with [maxFavorites].
             */
            data class LimitReached(val maxFavorites: Int) : Error()
        }
    }

    /**
     * Führt die Umschalt-Logik aus.
     * @param app Die App, die umgeschaltet wird.
     * @param currentMaxFavorites Das aktuelle UI-Limit (wird vom VM übergeben).
     */
    suspend operator fun invoke(app: AppInfo, currentMaxFavorites: Int): Result {
        // Cold flow (DATASTORE_READ_SPEC Belang A): favoriteComponentsFlow.first()
        // is a fresh read of the store — the count is always current, no replay
        // cache and no warm-subscriber assumption.
        val realFavoritesCount = favoritesRepository.favoriteComponentsFlow.first().size

        if (!favoritesRepository.isFavoriteComponent(app.componentName) &&
            realFavoritesCount >= currentMaxFavorites
        ) {
            return Result.Error.LimitReached(currentMaxFavorites)
        }

        val wasAdded = favoritesRepository.toggleFavoriteComponent(app.componentName)
        return if (wasAdded) Result.Success.Added else Result.Success.Removed
    }
}
