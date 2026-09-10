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
        // Cold flow (DATASTORE_READ_SPEC Belang A): a fresh read of the store — always
        // current, no replay cache, no warm-subscriber assumption.
        val favorites = favoritesRepository.favoriteComponentsFlow.first()
        val wasFavorite = app.componentName in favorites

        // Limit on DISTINCT PACKAGES, matching FavoritesRepositoryImpl.addFavoriteComponent
        // (a package with several launcher activities counts once). Counting COMPONENTS
        // here diverged from the store: it wrongly reported LimitReached for a new package
        // when the component count hit the limit but the package count had not.
        if (!wasFavorite) {
            val packages = favorites.mapTo(HashSet()) { it.substringBefore('/') }
            // app is an AppInfo: take the package straight from its structured key
            // instead of re-parsing the flattened string it just built.
            val newPackage = app.key.packageName
            if (newPackage !in packages && packages.size >= currentMaxFavorites) {
                return Result.Error.LimitReached(currentMaxFavorites)
            }
        }

        val wasAdded = favoritesRepository.toggleFavoriteComponent(app.componentName)
        return when {
            wasAdded -> Result.Success.Added
            // toggleFavoriteComponent returns false for BOTH a remove AND a rejected add
            // (the store's own package guard, or a malformed key). Disambiguate with the
            // pre-read state so a failed ADD is never mis-reported as a "Removed" success.
            wasFavorite -> Result.Success.Removed
            else -> Result.Error.LimitReached(currentMaxFavorites)
        }
    }
}
