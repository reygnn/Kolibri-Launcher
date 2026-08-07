package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.coroutines.flow.Flow

/**
 * Der Vertrag für die Verwaltung der *Reihenfolge* von favorisierten App-Einträgen.
 * Diese Logik arbeitet ausschließlich mit `componentName`s, da die Reihenfolge für
 * jeden einzelnen Launcher-Eintrag spezifisch ist.
 */
interface FavoritesOrderRepository : Purgeable {
    /**
     * Ein Flow, der die aktuelle, geordnete Liste der favorisierten `componentName`s bereitstellt.
     */
    val favoriteComponentsOrderFlow: Flow<List<String>>

    /**
     * Sortiert eine gegebene Liste von favorisierten App-Einträgen gemäss der gespeicherten Reihenfolge.
     */
    suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>): List<AppInfo>
    suspend fun saveOrder(orderedComponentNames : List<String>): Boolean

    /**
     * Reads the CURRENT favorites order straight from the store (fresh
     * `dataStore.data.first()` + the same JSON parsing as the flow), bypassing
     * the hot-shared [favoriteComponentsOrderFlow] replay cache. Used by any
     * point-read from a context without a warm Home subscriber — backup export
     * and Settings sort-favorites — each of which could otherwise capture a
     * stale replayed list (e.g. right after a backup restore). Fail-open on I/O (empty list),
     * mirroring the flow.
     */
    suspend fun getFavoriteComponentsOrderSnapshot(): List<String>
}