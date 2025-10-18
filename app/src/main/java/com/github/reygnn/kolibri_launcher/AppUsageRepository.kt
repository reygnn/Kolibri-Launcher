package com.github.reygnn.kolibri_launcher

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
}