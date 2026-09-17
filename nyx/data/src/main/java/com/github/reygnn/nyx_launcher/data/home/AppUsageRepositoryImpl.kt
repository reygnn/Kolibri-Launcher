package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.launcher.common.data.readFlowFailOpen
import com.github.reygnn.launcher.common.data.safePurge
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.isValidUsageTimestamp
import com.github.reygnn.launcher.core.timeWeightedUsageScore
import com.github.reygnn.nyx_launcher.data.di.UsageDataStore
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed [AppUsageRepository] over a SEPARATE store ([UsageDataStore]) — mirrors
 * kolibri's AppUsageRepositoryImpl, keyed by package name. One Preferences entry per app:
 * key `usage_<packageName>` ([AppConstants.KEY_USAGE_PREFIX]), value a `Set<String>` of
 * launch epoch-millis, capped at [AppConstants.MAX_TIMESTAMPS_PER_APP], validity-filtered on
 * each write. The scoring math is the shared `:core` [timeWeightedUsageScore] /
 * [isValidUsageTimestamp]; this class only owns persistence + the reactive snapshot.
 */
@Singleton
class AppUsageRepositoryImpl @Inject constructor(
    @param:UsageDataStore private val dataStore: DataStore<Preferences>,
) : AppUsageRepository {

    /**
     * Cold, fail-open read (an IOException recovers to an empty snapshot). The transform
     * projects the usage-key subset AND parses each package's timestamp strings to `Long`
     * once per change, so [distinctUntilChanged] fires only on a real usage change and the
     * downstream sort never re-reads/re-parses. Timestamps are carried raw; [scoreApps]
     * applies [isValidUsageTimestamp] against its own current time.
     */
    override val usageSnapshotFlow: Flow<Map<String, List<Long>>> =
        dataStore.readFlowFailOpen("Error reading usage flow") { preferences ->
            parseUsageSnapshot(preferences)
        }.distinctUntilChanged()

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

    override suspend fun recordPackageLaunch(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        val usageKey = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + packageName)
        val currentTime = System.currentTimeMillis()
        try {
            dataStore.edit { preferences ->
                val validTimestamps = (preferences[usageKey] ?: emptySet())
                    .mapNotNull { it.toLongOrNull() }
                    .filter { isValidUsageTimestamp(it, currentTime) }
                preferences[usageKey] = (validTimestamps + currentTime)
                    .sortedDescending()
                    .take(AppConstants.MAX_TIMESTAMPS_PER_APP)
                    .map { it.toString() }
                    .toSet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error recording launch for package: $packageName")
        }
    }

    override fun scoreApps(
        apps: List<LauncherApp>,
        usageSnapshot: Map<String, List<Long>>,
    ): Map<ComponentKey, Double> {
        // Pure: no store read (the snapshot is already parsed). Timestamps are validity-filtered
        // against now, since scoring is time-dependent.
        val currentTime = System.currentTimeMillis()
        return apps.associate { app ->
            val timestamps = usageSnapshot[app.key.packageName]
                ?.filter { isValidUsageTimestamp(it, currentTime) }
                .orEmpty()
            app.key to timeWeightedUsageScore(timestamps, currentTime)
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("AppUsageRepositoryImpl") { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(AppConstants.KEY_USAGE_PREFIX) }
                .forEach { preferences.remove(it) }
        }
    }
}
