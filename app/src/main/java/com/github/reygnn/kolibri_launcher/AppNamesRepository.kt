package com.github.reygnn.kolibri_launcher

// Das ist der Vertrag. Jede Klasse, die diesen Vertrag erfüllt,
// kann dem InstalledAppsManager als Helfer dienen.
interface AppNamesRepository : Purgeable {
    suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String
    suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean
    suspend fun removeCustomNameForPackage(packageName: String): Boolean
    suspend fun hasCustomNameForPackage(packageName: String): Boolean
    suspend fun triggerCustomNameUpdate()
}