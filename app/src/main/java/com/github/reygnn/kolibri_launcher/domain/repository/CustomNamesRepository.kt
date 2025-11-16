package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable

// Das ist der Vertrag. Jede Klasse, die diesen Vertrag erfüllt,
// kann dem InstalledAppsManager als Helfer dienen.
interface CustomNamesRepository : Purgeable {
    suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String
    suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean
    suspend fun removeCustomNameForPackage(packageName: String): Boolean
    suspend fun hasCustomNameForPackage(packageName: String): Boolean
    suspend fun triggerCustomNameUpdate()
    suspend fun getAllCustomNames(): Map<String, String>
    suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean
}