package com.github.reygnn.kolibri_launcher.domain.repository

import kotlinx.coroutines.flow.Flow

// Das ist der Vertrag. Jede Klasse, die diesen Vertrag erfüllt,
// kann dem InstalledAppsRepositoryImpl als Helfer dienen.
interface CustomNamesRepository : Purgeable {
    /**
     * Reactive view of all custom names as `packageName -> customName`
     * (REACTIVE_APPLIST_SPEC RAL-1). Emits the current mapping and re-emits
     * on every change, so consumers can fold the name in via `combine`
     * instead of forcing a full PackageManager re-enumeration on rename.
     * Distinct-until-changed: no emission when the mapping is unchanged.
     */
    val customNamesFlow: Flow<Map<String, String>>

    suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String
    suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean
    suspend fun removeCustomNameForPackage(packageName: String): Boolean
    suspend fun hasCustomNameForPackage(packageName: String): Boolean
    suspend fun triggerCustomNameUpdate()
    suspend fun getAllCustomNames(): Map<String, String>
    suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean

    /**
     * Reconciles custom names against the loaded app list, gating each removal
     * through [isStillPresent] — analogous to
     * [FavoritesRepository.reconcileFavoriteComponents] (RECONCILE_FIX_SPEC
     * R-INV-2). Custom names are package- (not component-) based, so
     * [isStillPresent] receives a package name. A name whose package is absent
     * from [installedPackageNames] is only a candidate; it is removed only if
     * [isStillPresent] returns false. Same fail-closed read for candidate and
     * delete; empty-installed guard lives at the caller.
     */
    suspend fun reconcileCustomNames(
        installedPackageNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    )
}