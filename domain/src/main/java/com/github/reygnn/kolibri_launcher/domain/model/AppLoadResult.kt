package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Definiert das Ergebnis des App-Ladevorgangs für das ViewModel.
 */
sealed class AppLoadResult {
    data object Success : AppLoadResult()
    data class Error(val messageResId: Int) : AppLoadResult()
}