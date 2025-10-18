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
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.json.JSONArray
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FavoritesOrderManager private constructor(
    private val dataStore: DataStore<Preferences>,
    private val context: Context,
    @param:ApplicationScope private val externalScope: CoroutineScope?,
    sharingStrategy: SharingStarted
) : FavoritesOrderRepository {

    override val favoriteComponentsOrderFlow: Flow<List<String>> = initializeFlow(sharingStrategy)

    private object PreferencesKeys {
        val ORDER_LIST = stringPreferencesKey("favorites_order_components_list_json")
    }

    /**
     * Der einzige öffentliche Konstruktor, der für Hilt sichtbar ist.
     */
    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        @ApplicationContext context: Context,
        @ApplicationScope externalScope: CoroutineScope?
    ) : this(
        dataStore = dataStore,
        context = context,
        externalScope = externalScope,
        sharingStrategy = SharingStarted.WhileSubscribed(5000L)
    )

    companion object {
        /**
         * Factory-Methode für Tests.
         */
        @VisibleForTesting
        internal fun createForTesting(
            dataStore: DataStore<Preferences>,
            context: Context,
            externalScope: CoroutineScope?,
            sharingStrategy: SharingStarted
        ): FavoritesOrderManager {
            return FavoritesOrderManager(dataStore, context, externalScope, sharingStrategy)
        }
    }

    private fun initializeFlow(sharingStrategy: SharingStarted): Flow<List<String>> {
        return dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading favorites order.")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { preferences ->
                val orderString = preferences[PreferencesKeys.ORDER_LIST]
                if (orderString.isNullOrBlank()) {
                    emptyList()
                } else {
                    try {
                        val jsonArray = JSONArray(orderString)
                        List(jsonArray.length()) { i -> jsonArray.getString(i) }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error parsing favorites order JSON.")
                        emptyList()
                    }
                }
            }
            .let { flow ->
                if (externalScope != null) {
                    flow.shareIn(
                        scope = externalScope,
                        started = sharingStrategy,
                        replay = 1
                    )
                } else {
                    flow
                }
            }
    }

    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        return try {
            val jsonArray = JSONArray(orderedComponentNames)
            val orderString = jsonArray.toString()

            dataStore.edit { preferences ->
                preferences[PreferencesKeys.ORDER_LIST] = orderString
            }

            Timber.d("Favorites order saved: $orderString")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error saving favorites order.")
            false
        }
    }

    /**
     * "Reine" Sortierfunktion: Sie hat keine internen Abhängigkeiten mehr.
     * Sie nimmt eine Liste von Apps und eine Liste der gewünschten Reihenfolge (als componentNames)
     * und gibt die sortierte App-Liste zurück.
     */
    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>): List<AppInfo> {
        return try {
            sortAppsWithGivenOrder(favoriteApps, order)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error sorting favorite components, falling back to alphabetical")
            // Fallback: Alphabetische Sortierung
            try {
                favoriteApps.sortedBy { it.displayName.lowercase() }
            } catch (e2: Exception) {
                TimberWrapper.silentError(e2, "Critical error in fallback sorting, returning unsorted list")
                favoriteApps
            }
        }
    }

    /**
     * Interne Hilfsfunktion, die jetzt mit componentName arbeitet.
     */
    internal fun sortAppsWithGivenOrder(appsToSort: List<AppInfo>, order: List<String>): List<AppInfo> {
        try {
            if (order.isEmpty()) {
                return appsToSort.sortedBy { it.displayName.lowercase() }
            }

            val orderedApps = mutableListOf<AppInfo>()
            val remainingApps = appsToSort.toMutableList()

            for (componentName in order) {
                val app = remainingApps.find { it.componentName == componentName }
                if (app != null) {
                    orderedApps.add(app)
                    remainingApps.remove(app)
                }
            }

            // Restliche Apps alphabetisch sortiert anhängen
            orderedApps.addAll(remainingApps.sortedBy { it.displayName.lowercase() })
            return orderedApps

        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in sortAppsWithGivenOrder, returning original list")
            return appsToSort
        }
    }

    open suspend fun removeComponentFromOrder(componentName: String): Boolean {
        return try {
            val currentOrder = favoriteComponentsOrderFlow.first().toMutableList()

            if (currentOrder.remove(componentName)) {
                saveOrder(currentOrder)
            } else {
                // Nicht in der Liste, also bereits "entfernt"
                true
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error removing component from order: $componentName")
            false
        }
    }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}