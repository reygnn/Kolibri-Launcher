package com.github.reygnn.kolibri_launcher

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
     * Sortiert eine gegebene Liste von favorisierten App-Einträgen gemäß der gespeicherten Reihenfolge.
     */
    suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>): List<AppInfo>
    suspend fun saveOrder(orderedComponentNames : List<String>): Boolean
}