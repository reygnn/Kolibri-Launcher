package com.github.reygnn.kolibri_launcher

/**
 * Eine generische sealed class, die den Zustand einer UI-Komponente darstellt,
 * die Daten aus einer asynchronen Quelle lädt.
 */
sealed class UiState<out T> {
    /** Der initiale Zustand oder während Daten geladen werden. */
    object Loading : UiState<Nothing>()

    /** Der Zustand, wenn Daten erfolgreich geladen wurden. */
    data class Success<T>(val data: T) : UiState<T>()

    /** Der Zustand, wenn beim Laden ein Fehler aufgetreten ist. */
    data class Error(val message: String) : UiState<Nothing>()
}