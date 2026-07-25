package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Der Vertrag für die Verwaltung von App-Nutzungsdaten.
 * Diese Logik arbeitet auf der Ebene von `packageName`, da die Nutzung für ein
 * gesamtes App-Paket aggregiert werden soll.
 */
interface AppUsageRepository : Purgeable {
    /**
     * Zeichnet einen Start für ein App-Paket auf.
     */
    suspend fun recordPackageLaunch(packageName: String?)

    /**
     * Sortiert eine Liste von Apps nach der zeitgewichteten Nutzung.
     */
    suspend fun sortAppsByTimeWeightedUsage(apps: List<AppInfo>): List<AppInfo>

    /**
     * Entfernt alle gespeicherten Nutzungsdaten für ein App-Paket.
     */
    suspend fun removeUsageDataForPackage(packageName: String?)

    /**
     * Prüft, ob für ein App-Paket Nutzungsdaten vorhanden sind.
     */
    suspend fun hasUsageDataForPackage(packageName: String?): Boolean

    /**
     * Returns the package names with usage data, ordered by their most
     * recent launch timestamp (newest first), capped at [limit]. Pure
     * recency — unlike [sortAppsByTimeWeightedUsage], which blends
     * frequency and recency. Packages whose timestamps are all invalid
     * or too old are skipped. Returns an empty list for a non-positive
     * [limit] or when no usage data exists.
     */
    suspend fun getRecentlyLaunchedPackages(limit: Int): List<String>
}