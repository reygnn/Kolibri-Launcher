package com.github.reygnn.kolibri_launcher.data

/**
 * Repository für das Zurücksetzen aller App-Daten
 * Koordiniert das Purging aller Purgeable Repositories
 */
interface ResetRepository {
    /**
     * Setzt alle Repositories auf ihre Standardwerte zurück
     * @return true wenn erfolgreich, false bei Fehler
     */
    suspend fun resetAllData(): Boolean

    /**
     * Setzt nur User-Daten zurück (Favoriten, Hidden Apps, Custom Names)
     * Behält Settings bei
     * @return true wenn erfolgreich, false bei Fehler
     */
    suspend fun resetUserData(): Boolean

    /**
     * Setzt nur Settings zurück, behält User-Daten bei
     * @return true wenn erfolgreich, false bei Fehler
     */
    suspend fun resetSettings(): Boolean
}