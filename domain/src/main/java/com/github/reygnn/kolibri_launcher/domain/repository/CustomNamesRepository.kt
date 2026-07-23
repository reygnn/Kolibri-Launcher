package com.github.reygnn.kolibri_launcher.domain.repository

// Das ist der Vertrag. Jede Klasse, die diesen Vertrag erfüllt,
// kann dem InstalledAppsRepositoryImpl als Helfer dienen.
interface CustomNamesRepository : Purgeable {
    suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String
    suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean
    suspend fun removeCustomNameForPackage(packageName: String): Boolean
    suspend fun hasCustomNameForPackage(packageName: String): Boolean
    suspend fun triggerCustomNameUpdate()
    suspend fun getAllCustomNames(): Map<String, String>
    suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean

    /**
     * Removes any custom name whose package is not in [installedPackageNames]
     * (app uninstalled). Custom names are package- (not component-) based, so
     * matching is done against package names. Called after every successful
     * app load from
     * [com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase],
     * analogous to [FavoritesRepository.cleanupFavoriteComponents]. The
     * empty-installed guard lives at the caller (guard on an empty app list),
     * so a cold start does not wipe custom names.
     */
    suspend fun cleanupCustomNames(installedPackageNames: List<String>)
}