package com.github.reygnn.kolibri_launcher.domain.repository

interface ResetRepository {
    /**
     * Setzt alle Repositories auf ihre Standardwerte zurück
     * (einschließlich App-Nutzungsdaten).
     * @return true wenn erfolgreich, false bei Fehler
     */
    suspend fun resetAllData(): Boolean

    /**
     * Setzt nur User-Daten zurück (Favoriten, Hidden Apps, Custom Names)
     * Behält Settings UND App-Nutzungsdaten bei.
     * @return true wenn erfolgreich, false bei Fehler
     */
    suspend fun resetUserData(): Boolean

    /**
     * Setzt nur Settings zurück, behält User-Daten bei.
     * @return true wenn erfolgreich, false bei Fehler
     */
    suspend fun resetSettings(): Boolean

    /**
     * NEU: Setzt nur die App-Nutzungsdaten (Sortierung) zurück.
     * @return true wenn erfolgreich, false bei Fehler
     */
    suspend fun resetAppUsageData(): Boolean
}