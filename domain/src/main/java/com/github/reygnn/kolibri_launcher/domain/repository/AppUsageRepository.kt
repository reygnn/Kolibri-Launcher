package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.coroutines.flow.Flow

/**
 * Der Vertrag für die Verwaltung von App-Nutzungsdaten.
 * Diese Logik arbeitet auf der Ebene von `packageName`, da die Nutzung für ein
 * gesamtes App-Paket aggregiert werden soll.
 */
interface AppUsageRepository : Purgeable {
    /**
     * Reactive usage snapshot (REACTIVE_APPLIST_SPEC): emits once initially and
     * again whenever the stored usage data changes, carrying the already-PARSED
     * timestamps (package name → launch epochs, millis). A consumer re-sorts by
     * passing the emitted map to [sortAppsByTimeWeightedUsage] — so the store is
     * read and parsed ONCE per real change, not re-read and re-parsed on every
     * drawer re-sort. Distinct on the usage key-set: a change to an unrelated
     * preference does not emit.
     */
    val usageSnapshotFlow: Flow<Map<String, List<Long>>>

    /**
     * Zeichnet einen Start für ein App-Paket auf.
     */
    suspend fun recordPackageLaunch(packageName: String?)

    /**
     * Sorts [apps] by time-weighted usage (descending) with an alphabetical
     * tie-break, scoring against [usageSnapshot] (from [usageSnapshotFlow]). A
     * PURE computation: it does not read the store — the snapshot is the
     * already-read, already-parsed data — so a drawer re-sort no longer re-reads
     * or re-parses per launch. Timestamps are still validity-filtered against the
     * current time here (scoring is time-dependent). `suspend` only for
     * repository-interface symmetry; it has no suspension point.
     */
    suspend fun sortAppsByTimeWeightedUsage(
        apps: List<AppInfo>,
        usageSnapshot: Map<String, List<Long>>,
    ): List<AppInfo>

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