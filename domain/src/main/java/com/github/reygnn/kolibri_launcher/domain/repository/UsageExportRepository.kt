package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult

/**
 * Repository für Export/Import von App-Nutzungsdaten.
 */
interface UsageExportRepository {
    /**
     * Exportiert alle Nutzungsdaten als JSON-String.
     */
    suspend fun exportToJson(): String

    /**
     * Importiert Nutzungsdaten aus einem JSON-String.
     * @param mergeWithExisting true = bestehende Daten behalten und mergen, false = ersetzen
     */
    suspend fun importFromJson(jsonString: String, mergeWithExisting: Boolean = true): UsageImportResult

    /**
     * Speichert den Export in eine Datei.
     */
    suspend fun saveToFile(uriString: String): Boolean

    /**
     * Lädt und importiert aus einer Datei.
     */
    suspend fun loadFromFile(uriString: String, mergeWithExisting: Boolean = true): UsageImportResult
}