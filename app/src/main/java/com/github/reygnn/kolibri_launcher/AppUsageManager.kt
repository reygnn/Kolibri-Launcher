/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Singleton
class AppUsageManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context
) : AppUsageRepository {

    /**
     * Zeichnet einen App-Start mit dem aktuellen Zeitstempel auf.
     */
    override suspend fun recordPackageLaunch(packageName: String?) {
        if (packageName.isNullOrBlank()) return

        val usageKey = stringSetPreferencesKey(packageName)
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
    override suspend fun sortAppsByTimeWeightedUsage(apps: List<AppInfo>): List<AppInfo> {
        if (apps.isEmpty()) return emptyList()

        return try {
            val allUsagePreferences = dataStore.data
                .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
                .first()

            val currentTime = System.currentTimeMillis()

            apps.map { appInfo ->
                val key = stringSetPreferencesKey(appInfo.packageName)
                val timestamps = allUsagePreferences[key]
                    ?.mapNotNull { it.toLongOrNull() }
                    ?.filter { isValidTimestamp(it, currentTime) }
                    ?: emptyList()

                val score = calculateTimeWeightedScore(timestamps, currentTime)
                Pair(appInfo, score)
            }
                .sortedWith(
                    compareByDescending<Pair<AppInfo, Double>> { it.second }
                        .thenBy { it.first.displayName.lowercase() }
                )
                .map { it.first }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error sorting by time-weighted usage, falling back to alphabetical sort")
            apps.sortedBy { it.displayName.lowercase() }
        }
    }

    /**
     * Berechnet den zeitgewichteten Score mit Safe-Math-Operations
     */
    private fun calculateTimeWeightedScore(timestamps: List<Long>, currentTime: Long): Double {
        if (timestamps.isEmpty()) return 0.0

        return try {
            timestamps.sumOf { launchTime ->
                try {
                    val timeDifferenceMs = currentTime - launchTime
                    if (timeDifferenceMs < 0) return@sumOf 0.0 // Zukunfts-Timestamp

                    val timeDifferenceSec = (timeDifferenceMs / 1000.0).coerceAtLeast(0.0)
                    val exponent = -AppConstants.USAGE_DECAY_LAMBDA * timeDifferenceSec

                    // Overflow-Schutz
                    when {
                        exponent < -100.0 -> 0.0
                        exponent > 100.0 -> 1.0
                        else -> exp(exponent).coerceIn(0.0, 1.0)
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error calculating score for timestamp: $launchTime")
                    0.0
                }
            }.coerceAtLeast(0.0)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in calculateTimeWeightedScore")
            0.0
        }
    }

    /**
     * Validiert einen Timestamp auf Plausibilität
     */
    private fun isValidTimestamp(timestamp: Long, currentTime: Long): Boolean {
        return try {
            timestamp > 0 &&
                    timestamp <= currentTime &&
                    (currentTime - timestamp) <= AppConstants.MAX_TIMESTAMP_AGE_MS
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Entfernt Nutzungsdaten für eine spezifische App (z.B. bei Deinstallation).
     */
    override suspend fun removeUsageDataForPackage(packageName: String?) {
        if (packageName.isNullOrBlank()) return

        val usageKey = stringSetPreferencesKey(packageName)
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
            val usageKey = stringSetPreferencesKey(packageName)
            val preferences = dataStore.data.first()
            preferences[usageKey]?.isNotEmpty() ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking usage data for package: $packageName")
            false
        }
    }

    override fun purgeRepository() { }
}