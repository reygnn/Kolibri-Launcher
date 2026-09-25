package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.launcher.core.Purgeable

import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository : Purgeable {
    val favoriteComponentsFlow: Flow<Set<String>>

    suspend fun isFavoriteComponent(componentName: String?): Boolean

    suspend fun toggleFavoriteComponent(componentName: String): Boolean
    suspend fun addFavoriteComponent(componentName: String): Boolean
    suspend fun removeFavoriteComponent(componentName: String): Boolean
    suspend fun saveFavoriteComponents(componentNames: List<String>)

    /**
     * Reads the CURRENT favorite components straight from the store (fresh
     * `dataStore.data.first()`), bypassing the hot-shared [favoriteComponentsFlow]
     * replay cache. Used by any point-read from a context without a warm Home
     * subscriber — backup export, Settings sort-favorites, Onboarding
     * edit-favorites — each of which could otherwise capture a stale replayed
     * set (e.g. right after a backup restore). Fail-open: a
     * read error yields the empty default (mirrors the flow), never throws for I/O.
     */
    suspend fun getFavoriteComponentsSnapshot(): Set<String>

    /**
     * Reads the current favorites for the EDIT-favorites editor pre-selection as a
     * DISTINGUISHABLE result (DATASTORE_READ_SPEC Belang C): [FavoritesEditRead.Loaded]
     * on a successful read, [FavoritesEditRead.Unavailable] on an I/O failure — never
     * an empty set masquerading as "no favorites". Fail-CLOSED counterpart to the
     * fail-open [getFavoriteComponentsSnapshot], because this read feeds a subsequent
     * SAVE: an unreadable store must NOT let the editor persist an empty set and wipe
     * the real favorites (DSR-INV-4). Cancellation always propagates.
     */
    suspend fun readFavoritesForEdit(): FavoritesEditRead
}