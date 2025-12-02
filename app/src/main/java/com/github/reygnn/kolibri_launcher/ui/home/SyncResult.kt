package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Das Ergebnis der Synchronisations-Prüfung.
 * Sealed Interfaces zwingen uns, jeden Fall im 'when' zu behandeln.
 */
sealed interface SyncResult {

    // Alles gut, keine Änderung nötig.
    data object UpToDate : SyncResult

    // Hoppla, wir müssen was tun! Enthält direkt den neuen Wert.
    data class CorrectionNeeded(
        val oldOrientation: Int,
        val newOrientation: Int
    ) : SyncResult
}