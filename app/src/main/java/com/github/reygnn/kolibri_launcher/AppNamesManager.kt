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
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * =====================================================================================
 * ARCHITECTURAL NOTE: Why this manager uses an event trigger instead of exposing a Flow
 * =====================================================================================
 *
 * A common question is why this manager doesn't follow the same pattern as
 * AppVisibilityManager, which exposes its state via `hiddenAppsFlow: Flow<Set<String>>`.
 * While it's tempting to have this manager expose a `Flow<Map<String, String>>`
 * (mapping package names to custom names), doing so would be an anti-pattern for
 * this specific use case due to fundamental differences in data structure,
 * access patterns, and performance.
 *
 * The current approach, using a central `appsUpdateTrigger`, is intentionally chosen
 * for the following reasons:
 *
 * 1.  **State vs. Event: The Core Distinction**
 *     -   `AppVisibilityManager` manages a single, holistic STATE: the complete set of
 *         all hidden apps. Consumers, like `InstalledAppsManager`, need this entire
 *         set at once to perform a filter operation on the master app list. Exposing
 *         this state as a `Flow` is therefore the correct and efficient pattern.
 *     -   `AppNamesManager`, in contrast, deals with granular EVENTS. A user changing
 *         a single app's name is an event, not a complete state change. The primary
 *         consumer (`InstalledAppsManager`) does not need the entire map of all
 *         custom names simultaneously. It just needs a signal that *something*
 *         changed, prompting it to rebuild its own state.
 *
 * 2.  **Performance and Efficiency**
 *     -   **Inefficiency of a Flow:** To construct and emit a `Flow<Map<String, String>>`,
 *         this manager would need to read ALL preferences from DataStore, iterate through
 *         all keys to find the ones related to names, and build a complete Map object.
 *         This would happen EVERY TIME a single name is changed, creating significant
 *         and unnecessary overhead.
 *     -   **Efficiency of On-Demand Access:** The current design is highly optimized.
 *         When `InstalledAppsManager` processes its list, it calls `getDisplayNameForPackage()`
 *         for each app. This is a fast, targeted, on-demand query for the exact piece of
 *         information it needs at that moment. The overall process of firing one
 *         lightweight `Unit` event and then performing many fast, individual lookups
 *         is far more performant.
 *
 * 3.  **The Current Solution IS Already Reactive and Decoupled**
 *     -   The `appsUpdateTrigger` (a `MutableSharedFlow`) acts as a lightweight, decoupled
 *         **Event Bus**.
 *     -   **Publisher:** `AppNamesManager` publishes an event ("a name changed") without
 *         knowing or caring who is listening.
 *     -   **Subscriber:** `InstalledAppsManager` subscribes to these events and decides
 *         how to react (by reloading its app list).
 *     -   This pattern perfectly achieves the reactive goal—the UI updates automatically
 *         in response to a change—without the performance penalty and complexity of
 *         managing and combining multiple large state flows.
 *
 * **Conclusion:**
 * While exposing state via a `Flow` is a powerful reactive pattern, it is not a
 * one-size-fits-all solution. For this manager, where data changes are granular events
 * and consumption is on-demand, the event-based trigger mechanism is the more
 * appropriate, performant, and scalable architecture.
 *
 * @see com.github.reygnn.kolibri_launcher.AppVisibilityManager for an example where a Flow is the correct pattern.
 */



/**
 * =====================================================================================
 * ARCHITECTURAL NOTE #2: Single Source of Truth vs. Granular "Patch" Events
 * =====================================================================================
 *
 * A tempting alternative to our current event-based system is to make the trigger
 * more granular. Instead of emitting a generic `Unit` signal (meaning "something
 * changed"), one might suggest emitting the specific `packageName` of the app that
 * was updated. The idea is to allow the consumer (`InstalledAppsManager`) to perform
 * a "surgical" update by patching a single item in its list, rather than rebuilding
 * the entire list.
 *
 * While this sounds more performant on the surface, it introduces significant
 * complexity and violates core principles of modern, state-driven UI development,
 * especially when dealing with a sorted list presented by a RecyclerView.
 * This approach is deliberately avoided for the following critical reasons:
 *
 * 1.  **The Sorting Problem: The Deal-Breaker**
 *     The primary consumer of this data is a UI that displays a list of apps sorted
 *     alphabetically by their `displayName`. A granular update event breaks this contract.
 *
 *     *Scenario:*
 *     1. The user renames "Zebra" (at the end of the list) to "Apple" (at the beginning).
 *     2. A granular event `emit("com.zebra.app")` is sent.
 *     3. The `InstalledAppsManager` receives this event. To handle it, it would have to:
 *        a. Find the old "Zebra" `AppInfo` object in its current list.
 *        b. Create a new `AppInfo` object with the display name "Apple".
 *        c. Create a new list by replacing the old object with the new one.
 *     4. **The result is a corrupted list:** The new "Apple" item is now incorrectly
 *        positioned at the end of the list where "Zebra" used to be. The list is no
 *        longer alphabetically sorted.
 *
 *     To fix this, the manager would have to **re-sort the entire list anyway**, completely
 *     negating any perceived performance benefit of the granular event. The complexity
 *     of patching a single item followed by a full re-sort is strictly worse than
 *     simply rebuilding the list from a clean state.
 *
 * 2.  **Violating the "Single Source of Truth" Principle**
 *     Modern reactive architecture, as used with Jetpack Compose or `ListAdapter`/`DiffUtil`,
 *     thrives on the "Single Source of Truth" (SSoT) pattern. State is represented
 *     by immutable objects (like a `List<AppInfo>`). When a change occurs, the SSoT
 *     (here, `InstalledAppsManager`) computes and emits a completely new, consistent
 *     state object.
 *
 *     The "patching" approach turns the `InstalledAppsManager` into a stateful, error-prone
 *     cache that attempts to manually synchronize its state based on partial information.
 *     This is a fragile design that leads to subtle bugs and race conditions. Our
 *     current "rebuild from scratch" approach guarantees that every emitted list is
 *     atomically correct, consistent, and properly sorted.
 *
 * 3.  **The UI Layer is Already Optimized for This**
 *     The perceived inefficiency of emitting a whole new list is a misconception. The
 *     `RecyclerView.ListAdapter` and its underlying `DiffUtil` are specifically designed
 *     for this exact scenario.
 *
 *     When `DiffUtil` receives a new list, it performs a highly efficient diffing
 *     algorithm to calculate the *minimal set of changes* required to update the UI.
 *     If only one item's name changed and its position moved, `DiffUtil` will correctly
 *     identify this as one `onItemChanged` and one `onItemMoved` event, resulting in a
 *     smooth and performant animation.
 *
 *     In essence, **we delegate the UI optimization task to the UI layer, where it belongs**,
 *     instead of prematurely optimizing the data layer with a more complex and fragile event system.
 *
 * **Conclusion:**
 * The current architecture, where `AppNamesManager` sends a simple, generic signal and
 * `InstalledAppsManager` rebuilds its state from the ground up, is the superior design.
 * It is simpler, more robust, guarantees data consistency (especially sorting), and
 * correctly leverages the powerful optimization tools provided by the Android UI toolkit.
 * Adopting a granular event system would introduce significant complexity for no tangible
 * benefit.
 */


@Singleton
class AppNamesManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>,
    @param:ApplicationContext private val context: Context
) : AppNamesRepository {

    /**
     * Setzt einen benutzerdefinierten Namen für eine App. Wenn der Name leer ist, wird er entfernt.
     * Nach einer erfolgreichen Änderung wird ein globales Update angestoßen.
     */
    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        // Die Logik, ob ein Name entfernt oder gesetzt wird, ist hier gekapselt.
        val isSuccessful = if (customName.isBlank()) {
            // Rufe die interne Logik zum Entfernen auf, um doppelten Trigger-Code zu vermeiden.
            removeCustomNameInternal(packageName)
        } else {
            // Logik zum Setzen/Aktualisieren des Namens.
            runCatching {
                val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
                dataStore.edit { preferences ->
                    preferences[nameKey] = customName.trim()
                }
                true
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                TimberWrapper.silentError(e, "Error setting custom name for package: $packageName")
                false
            }
        }

        // Wenn die Operation (Setzen oder Entfernen) erfolgreich war, benachrichtige die Listener.
        if (isSuccessful) {
            triggerCustomNameUpdate()
        }
        return isSuccessful
    }

    /**
     * Entfernt einen benutzerdefinierten Namen für eine App.
     * Diese Methode wird vom ViewModel aufgerufen.
     */
    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        val isSuccessful = removeCustomNameInternal(packageName)

        // Wenn die Operation erfolgreich war, benachrichtige die Listener.
        if (isSuccessful) {
            triggerCustomNameUpdate()
        }
        return isSuccessful
    }

    /**
     * Die eigentliche Logik zum Entfernen, ohne den Trigger auszulösen,
     * um von anderen Funktionen wiederverwendet zu werden.
     */
    private suspend fun removeCustomNameInternal(packageName: String): Boolean {
        return runCatching {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            dataStore.edit { preferences ->
                preferences.remove(nameKey)
            }
            true
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            TimberWrapper.silentError(e, "Error removing custom name for package: $packageName")
            false
        }
    }

    /**
     * Gibt den benutzerdefinierten Anzeigenamen zurück, falls vorhanden,
     * andernfalls den übergebenen Originalnamen.
     */
    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
        return runCatching {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            val preferences = dataStore.data.first()
            preferences[nameKey] ?: originalName
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            TimberWrapper.silentError(e, "Error getting display name for package: $packageName")
            originalName
        }
    }

    /**
     * Prüft, ob für eine App ein benutzerdefinierter Name existiert.
     */
    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return runCatching {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            val preferences = dataStore.data.first()
            preferences.contains(nameKey)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            TimberWrapper.silentError(e, "Error checking custom name for package: $packageName")
            false
        }
    }

    /**
     * Stösst ein Event im zentralen Update-Trigger an.
     * Dies signalisiert anderen Teilen der App (wie dem InstalledAppsManager),
     * dass sie ihre Daten neu laden sollten.
     */
    override suspend fun triggerCustomNameUpdate() {
        Timber.d("[DATAFLOW] AppNamesManager is emitting an update event.")
        appsUpdateTrigger.emit(Unit)
    }

    override fun purgeRepository() { }  // für die androidTests
}