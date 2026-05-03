package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Definiert das Ergebnis des App-Ladevorgangs für das ViewModel.
 *
 * UI-Layer maps the [Failure] payload to `R.string.*` resources at the
 * call site. Sealed instead of `@StringRes Int` so the domain stays free
 * of `androidx.annotation`.
 */
sealed class AppLoadResult {
    data object Success : AppLoadResult()
    data class Error(val failure: Failure) : AppLoadResult()

    sealed class Failure {
        /** The app list could not be loaded and there's no cached fallback. */
        object NotLoaded : Failure()
    }
}
