package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for custom app name overrides with event-driven architecture.
 *
 * This singleton allows users to set personalized display names for installed apps,
 * persisting them in DataStore. Instead of exposing state via a Flow, it uses an
 * event-based trigger system to notify consumers of changes—a deliberate architectural
 * decision optimized for this specific use case.
 *
 * **Core Functionality:**
 * - Store/retrieve custom app names keyed by package name
 * - Remove custom names (reverting to original names)
 * - Check existence of custom names
 * - Trigger global update events on changes
 *
 * **Architecture: Event-Based Design (Not Flow-Based)**
 *
 * Unlike `AppVisibilityManager` which exposes `Flow<Set<String>>`, this manager
 * uses `MutableSharedFlow<Unit>` as a lightweight event bus. This design choice
 * is intentional and superior for the following reasons:
 *
 * **1. Events vs. State:**
 * - Visibility management is holistic STATE (entire hidden set needed at once)
 * - Name management involves granular EVENTS (single name changes)
 * - Consumers only need a signal to refresh, not the entire name map
 *
 * **2. Performance:**
 * - Flow approach would require reading ALL preferences and building a complete
 *   Map on every single name change—expensive and wasteful
 * - Current approach: emit one lightweight `Unit` event, then consumers perform
 *   fast, targeted on-demand lookups via `getDisplayNameForPackage()`
 * - Overall: emit(Unit) + N×fast_lookup >> emit(Map) for this use case
 *
 * **3. Already Reactive:**
 * - `appsUpdateTrigger` acts as a decoupled event bus
 * - Publisher (this manager) emits events without knowing subscribers
 * - Subscriber (`InstalledAppsRepositoryImpl`) reacts by rebuilding its state
 * - Achieves reactivity without Flow complexity or performance penalty
 *
 * **Why Not Granular Events (emit packageName)?**
 *
 * While emitting the specific changed package seems more efficient, it would
 * actually harm the architecture:
 *
 * **The Sorting Problem:**
 * - App list is sorted alphabetically by display name
 * - Renaming "Zebra" → "Apple" requires moving the item from end to beginning
 * - A granular patch would create unsorted list, requiring full re-sort anyway
 * - Result: added complexity with zero performance gain
 *
 * **Single Source of Truth:**
 * - Modern reactive UI (Compose, ListAdapter) expects immutable state objects
 * - "Patching" turns manager into fragile stateful cache with race conditions
 * - "Rebuild from scratch" guarantees atomic correctness and proper sorting
 *
 * **UI Layer Optimization:**
 * - `DiffUtil` is specifically designed to handle new list emissions efficiently
 * - It calculates minimal UI changes (onItemChanged + onItemMoved)
 * - We delegate optimization to the UI layer where it belongs
 *
 * **Data Flow:**
 * 1. User changes app name via ViewModel
 * 2. Manager persists to DataStore
 * 3. Manager emits Unit event via `appsUpdateTrigger`
 * 4. `InstalledAppsRepositoryImpl` receives event and rebuilds app list
 * 5. UI updates via DiffUtil with smooth animations
 *
 * **Error Handling:**
 * All operations return Boolean success indicators and log failures silently.
 * [java.util.concurrent.CancellationException] is always re-thrown for proper coroutine cancellation.
 *
 * @property dataStore Preferences DataStore for persisting custom names
 * @property appsUpdateTrigger Shared event bus for notifying consumers of changes
 *
 * @see HiddenAppsRepositoryImpl for an example where Flow-based state exposure is correct
 */
@Singleton
class CustomNamesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>
) : CustomNamesRepository {

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
            try {
                val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
                dataStore.edit { preferences ->
                    preferences[nameKey] = customName.trim()
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
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
        return try {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            dataStore.edit { preferences ->
                preferences.remove(nameKey)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing custom name for package: $packageName")
            false
        }
    }

    /**
     * Gibt den benutzerdefinierten Anzeigenamen zurück, falls vorhanden,
     * andernfalls den übergebenen Originalnamen.
     */
    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
        return try {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            val preferences = dataStore.data.first()
            preferences[nameKey] ?: originalName
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting display name for package: $packageName")
            originalName
        }
    }

    /**
     * Prüft, ob für eine App ein benutzerdefinierter Name existiert.
     */
    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return try {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            val preferences = dataStore.data.first()
            preferences.contains(nameKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking custom name for package: $packageName")
            false
        }
    }

    /**
     * Stösst ein Event im zentralen Update-Trigger an.
     * Dies signalisiert anderen Teilen der App (wie dem InstalledAppsRepositoryImpl),
     * dass sie ihre Daten neu laden sollten.
     */
    override suspend fun triggerCustomNameUpdate() {
        try {
            Timber.d("[DATAFLOW] AppNamesManager is emitting an update event.")
            appsUpdateTrigger.emit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error triggering custom name update")
        }
    }

    /**
     * Gibt alle benutzerdefinierten App-Namen als Map zurück.
     * Für Backup/Export-Zwecke.
     */
    override suspend fun getAllCustomNames(): Map<String, String> {
        return try {
            val preferences = dataStore.data.first()
            // Single pass over the snapshot: read key + value together instead
            // of re-fetching each value via preferences[key].
            val customNames = preferences.asMap().entries.mapNotNull { (key, value) ->
                if (key.isCustomNameKey() && value is String) key.customNamePackage() to value else null
            }.toMap()

            Timber.d("Retrieved ${customNames.size} custom app names")
            customNames
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting all custom names")
            emptyMap()
        }
    }

    /**
     * Setzt mehrere benutzerdefinierte Namen gleichzeitig (Batch-Operation).
     * Optimiert für Backup-Import - triggert nur einmal am Ende statt nach jedem Namen.
     */
    override suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean {
        if (names.isEmpty()) return true

        return try {
            // Alle Namen in einer DataStore-Transaction setzen
            dataStore.edit { preferences ->
                names.forEach { (packageName, customName) ->
                    val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
                    if (customName.isNotBlank()) {
                        preferences[nameKey] = customName.trim()
                    }
                }
            }

            Timber.i("Batch set ${names.size} custom app names")

            // Trigger nur einmal am Ende
            triggerCustomNameUpdate()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting custom names in batch")
            false
        }
    }

    override suspend fun cleanupCustomNames(installedPackageNames: List<String>) {
        try {
            val installedSet = installedPackageNames.toSet()
            dataStore.edit { preferences ->
                // Remove entries whose package is no longer installed. Snapshot
                // the orphan keys first, then remove — no concurrent
                // modification of the underlying map.
                val orphanKeys = preferences.customNameKeys()
                    .filter { it.customNamePackage() !in installedSet }
                if (orphanKeys.isNotEmpty()) {
                    // Log only the count, never package names (PII).
                    Timber.w("Removed ${orphanKeys.size} orphaned custom names")
                    orphanKeys.forEach { preferences.remove(it) }
                }
            }
            // Intentionally NO triggerCustomNameUpdate(): this runs inside the
            // app-load pipeline; emitting an update here would re-trigger a
            // reload and loop. The removed names are for uninstalled packages,
            // so no currently-visible app name is affected.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to cleanup custom names, keeping current state")
        }
    }

    override suspend fun purgeRepository() {
        try {
            dataStore.edit { preferences ->
                preferences.customNameKeys().forEach { preferences.remove(it) }
            }
            triggerCustomNameUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to purge CustomNamesRepositoryImpl repository")
        }
    }

    /**
     * True for a DataStore key that holds a custom name, i.e. carries the
     * [AppConstants.KEY_NAME_PREFIX] prefix ("name_<packageName>"). The single
     * definition of the key convention, shared by read/cleanup/purge.
     */
    private fun Preferences.Key<*>.isCustomNameKey(): Boolean =
        name.startsWith(AppConstants.KEY_NAME_PREFIX)

    /** The package name encoded in a `name_<packageName>` key. */
    private fun Preferences.Key<*>.customNamePackage(): String =
        name.removePrefix(AppConstants.KEY_NAME_PREFIX)

    /**
     * All keys holding a custom name. Shared by cleanup/purge. Works on both a
     * read snapshot and the
     * [androidx.datastore.preferences.core.MutablePreferences] inside `edit`.
     */
    private fun Preferences.customNameKeys(): List<Preferences.Key<*>> =
        asMap().keys.filter { it.isCustomNameKey() }
}