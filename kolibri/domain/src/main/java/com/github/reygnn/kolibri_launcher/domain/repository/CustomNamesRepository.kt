package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.launcher.core.Purgeable

import kotlinx.coroutines.flow.Flow

// Das ist der Vertrag. Jede Klasse, die diesen Vertrag erfüllt,
// kann dem InstalledAppsRepositoryImpl als Helfer dienen.
interface CustomNamesRepository : Purgeable {
    /**
     * Reactive view of all custom names as `packageName -> customName`
     * (REACTIVE_APPLIST_SPEC RAL-1). Emits the current mapping and re-emits
     * on every change, so consumers can fold the name in via `combine`
     * instead of forcing a full app re-enumeration on rename.
     * Distinct-until-changed: no emission when the mapping is unchanged.
     */
    val customNamesFlow: Flow<Map<String, String>>

    suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String
    suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean
    suspend fun removeCustomNameForPackage(packageName: String): Boolean
    suspend fun hasCustomNameForPackage(packageName: String): Boolean
    suspend fun getAllCustomNames(): Map<String, String>
    suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean
}