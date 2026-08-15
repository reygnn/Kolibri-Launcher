package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.timeWeightedUsageScore
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.di.UsageDataStore
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for tracking and analyzing app usage patterns with time-weighted scoring.
 *
 * This singleton persists app launch timestamps using DataStore and provides intelligent
 * sorting based on both frequency and recency of usage. The core algorithm uses exponential
 * decay to give more weight to recent launches while still considering overall usage patterns.
 *
 * **Time-Weighted Scoring Algorithm:**
 * Each app launch receives a score that decays exponentially over time:
 * ```
 * score = e^(-λ × Δt)
 * ```
 * Where:
 * - λ (lambda) = 0.000001 (decay constant)
 * - Δt = time since launch in seconds
 * - e = Euler's number (≈2.71828)
 *
 * **Score Decay Examples:**
 * - After 1 day: ~91.7% of original value
 * - After 7 days: ~54.8%
 * - After 30 days: ~10.5%
 *
 * An app's total score is the sum of all its individual launch scores, balancing
 * both frequency (number of launches) and recency (how recent the launches were).
 *
 * **Data Management:**
 * - Stores up to [AppConstants.MAX_TIMESTAMPS_PER_APP] timestamps per app
 * - Automatically validates and cleans invalid/old timestamps
 * - Filters out timestamps older than [AppConstants.MAX_TIMESTAMP_AGE_MS]
 * - Uses DataStore for persistent, async storage
 *
 * **Sorting Behavior:**
 * Primary sort: Time-weighted score (descending)
 * Secondary sort: Display name alphabetically (tie-breaker)
 *
 * **Error Handling:**
 * All operations are protected against failures and return sensible defaults:
 * - Failed sorting falls back to alphabetical order
 * - Failed recordings are logged but don't crash
 * - Invalid data is filtered out during processing
 * - [kotlinx.coroutines.CancellationException] is always re-thrown for proper coroutine cancellation
 *
 * @property dataStore Preferences DataStore for persisting usage timestamps
 * @property context Application context for system access
 */
@Singleton
class AppUsageRepositoryImpl @Inject constructor(
    @param:UsageDataStore private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context
) : AppUsageRepository {

    /**
     * Reactive usage snapshot (REACTIVE_APPLIST_SPEC). Cold, fail-open read
     * (DATASTORE_READ_SPEC): an [IOException] recovers to an empty snapshot. The
     * transform projects the [AppConstants.KEY_USAGE_PREFIX] subset AND parses each
     * package's timestamp strings to `Long` exactly ONCE per change — so
     * [distinctUntilChanged] fires solely on a real usage change, and downstream
     * [sortAppsByTimeWeightedUsage] no longer re-reads or re-parses the store on
     * every re-sort. Timestamps are carried raw (not age-filtered); the sort
     * applies [isValidTimestamp] against its own current time.
     */
    override val usageSnapshotFlow: Flow<Map<String, List<Long>>> =
        dataStore.readFlowFailOpen("Error reading usage flow") { preferences ->
            parseUsageSnapshot(preferences)
        }
            .distinctUntilChanged()

    /**
     * Projects the usage-key subset of [preferences] and parses each package's
     * stored timestamp strings to `Long` (dropping non-numeric). Keyed by package
     * name (prefix stripped). Empty lists are omitted. Pure — cannot throw.
     */
    private fun parseUsageSnapshot(preferences: Preferences): Map<String, List<Long>> {
        val result = HashMap<String, List<Long>>()
        for ((key, value) in preferences.asMap()) {
            if (!key.name.startsWith(AppConstants.KEY_USAGE_PREFIX)) continue
            @Suppress("UNCHECKED_CAST")
            val timestamps = (value as? Set<String>) ?: continue
            val parsed = timestamps.mapNotNull { it.toLongOrNull() }
            if (parsed.isNotEmpty()) {
                result[key.name.removePrefix(AppConstants.KEY_USAGE_PREFIX)] = parsed
            }
        }
        return result
    }

    /**
     * Zeichnet einen App-Start mit dem aktuellen Zeitstempel auf.
     */
    override suspend fun recordPackageLaunch(packageName: String?) {
        if (packageName.isNullOrBlank()) return

        val usageKey = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + packageName)
        val currentTime = System.currentTimeMillis()

        try {
            dataStore.edit { preferences ->
                val currentTimestamps = preferences[usageKey] ?: emptySet()

                // Validierung und Bereinigung alter Timestamps
                val validTimestamps = currentTimestamps
                    .mapNotNull { it.toLongOrNull() }
                    .filter { isValidTimestamp(it, currentTime) }

                val updatedTimestamps = (validTimestamps + currentTime)
                    .sortedDescending()
                    .take(AppConstants.MAX_TIMESTAMPS_PER_APP)
                    .map { it.toString() }
                    .toSet()

                preferences[usageKey] = updatedTimestamps
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error recording launch for package: $packageName")
        }
    }

    /**
     * Sortiert eine Liste von Apps nach der zeitgewichteten Nutzung (absteigend)
     * und dann alphabetisch als Tie-Breaker.
     *
     * Berechnung der Zerfallsrate:
     * Für jeden App-Start wird ein Score berechnet, der mit der Zeit exponentiell abnimmt.
     * Die Formel lautet: score = e^(-λ * Δt)
     *
     * Dabei gilt:
     * - λ (Lambda) = 0.000001 ist die Zerfallskonstante
     * - Δt = Zeit seit dem App-Start in Sekunden
     * - e = Eulersche Zahl (≈ 2.71828)
     *
     * Beispiel:
     * - Nach 1 Tag (86.400 Sekunden): score ≈ 0.917 (91,7% des ursprünglichen Werts)
     * - Nach 7 Tagen: score ≈ 0.548 (54,8%)
     * - Nach 30 Tagen: score ≈ 0.105 (10,5%)
     *
     * Der Gesamt-Score einer App ist die Summe aller einzelnen Start-Scores.
     * So werden sowohl Häufigkeit als auch Aktualität der Nutzung berücksichtigt:
     * - Häufige, aktuelle Starts → hoher Score
     * - Seltene oder alte Starts → niedriger Score
     */
    override suspend fun sortAppsByTimeWeightedUsage(
        apps: List<AppInfo>,
        usageSnapshot: Map<String, List<Long>>,
    ): List<AppInfo> {
        if (apps.isEmpty()) return emptyList()

        // Pure: no store read, no re-parse — the snapshot is already parsed. No
        // try/catch (Rule 11): the map lookup, the validity filter, the scoring
        // (timeWeightedUsageScore) and the sort are pure and cannot throw. The
        // graceful alphabetical fallback lives in the caller (GetDrawerAppsUseCase),
        // and an empty snapshot already degrades to the alphabetical tie-break.
        val currentTime = System.currentTimeMillis()

        return apps
            .map { appInfo ->
                val timestamps = usageSnapshot[appInfo.packageName]
                    ?.filter { isValidTimestamp(it, currentTime) }
                    ?: emptyList()
                appInfo to timeWeightedUsageScore(timestamps, currentTime)
            }
            .sortedWith(
                compareByDescending<Pair<AppInfo, Double>> { it.second }
                    .thenBy { it.first.displayNameLower }
            )
            .map { it.first }
    }

    override suspend fun getRecentlyLaunchedPackages(limit: Int): List<String> {
        if (limit <= 0) return emptyList()

        return try {
            val allUsagePreferences = dataStore.data
                .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
                .first()

            val currentTime = System.currentTimeMillis()

            allUsagePreferences.asMap()
                .mapNotNull { (key, value) ->
                    if (!key.name.startsWith(AppConstants.KEY_USAGE_PREFIX)) return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val timestamps = (value as? Set<String>) ?: return@mapNotNull null
                    val lastLaunch = timestamps
                        .mapNotNull { it.toLongOrNull() }
                        .filter { isValidTimestamp(it, currentTime) }
                        .maxOrNull()
                        ?: return@mapNotNull null
                    val packageName = key.name.removePrefix(AppConstants.KEY_USAGE_PREFIX)
                    packageName to lastLaunch
                }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading recently launched packages")
            emptyList()
        }
    }

    /**
     * Validates a timestamp for plausibility.
     * Pure Long comparisons and subtraction — these don't throw.
     */
    private fun isValidTimestamp(timestamp: Long, currentTime: Long): Boolean {
        return timestamp > 0 &&
                timestamp <= currentTime &&
                (currentTime - timestamp) <= AppConstants.MAX_TIMESTAMP_AGE_MS
    }

    /**
     * Entfernt Nutzungsdaten für eine spezifische App (z.B. bei Deinstallation).
     */
    override suspend fun removeUsageDataForPackage(packageName: String?) {
        if (packageName.isNullOrBlank()) return

        val usageKey = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + packageName)
        try {
            dataStore.edit { preferences ->
                preferences.remove(usageKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing usage data for package: $packageName")
        }
    }

    override suspend fun hasUsageDataForPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false

        return try {
            val usageKey = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + packageName)
            val preferences = dataStore.data.first()
            preferences[usageKey]?.isNotEmpty() ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking usage data for package: $packageName")
            false
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("AppUsageRepositoryImpl") { preferences ->
            val keysToRemove = preferences.asMap().keys
                .filter { key ->
                    key.name.startsWith(AppConstants.KEY_USAGE_PREFIX)
                }

            keysToRemove.forEach { key ->
                preferences.remove(key)
            }
        }
    }
}