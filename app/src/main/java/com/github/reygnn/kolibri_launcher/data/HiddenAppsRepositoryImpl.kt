package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for app visibility state with reactive Flow-based architecture.
 *
 * This singleton manages which apps are hidden from the launcher's app list by
 * persisting component identifiers in DataStore and exposing them as a reactive
 * Flow. Unlike `AppNamesManager`, this manager uses Flow-based state exposure—a
 * deliberate architectural choice that is optimal for visibility management.
 *
 * **Core Functionality:**
 * - Hide/show individual apps by component name
 * - Batch update multiple app visibilities atomically
 * - Check if specific app is hidden
 * - Expose complete hidden set reactively via Flow
 *
 * **Architecture: Flow-Based State Exposure**
 *
 * This manager exposes `hiddenAppsFlow: Flow<Set<String>>` rather than using
 * an event-based trigger system. This design is intentional and correct for
 * the following reasons:
 *
 * **1. Holistic State Management:**
 * - Visibility is a complete STATE, not granular events
 * - Consumers need the ENTIRE set of hidden apps at once to perform filtering
 * - `InstalledAppsRepositoryImpl` filters its master list by checking against this set
 * - Flow naturally represents "current complete state" that changes over time
 *
 * **2. Efficient Consumption Pattern:**
 * - Consumer pattern: "Is app X in the hidden set?" (set membership test)
 * - This is O(1) lookup on a Set—extremely fast
 * - Consumer needs the full set anyway, so building/emitting it has no waste
 * - Unlike name lookups (many individual queries), visibility is one holistic check
 *
 * **3. True Reactive Benefits:**
 * - DataStore naturally provides Flow, so we preserve that reactivity
 * - Any change to hidden set automatically propagates to all observers
 * - No manual trigger coordination needed—DataStore handles it
 * - Consumers can `combine()` this Flow with other Flows declaratively
 *
 * **4. Structural Simplicity:**
 * - Single set in DataStore vs. many individual name entries
 * - Reading one preference key vs. iterating all keys with a prefix
 * - Natural fit for Flow emission—no overhead in exposing complete state
 *
 * **Contrast with AppNamesManager:**
 * - Names: Granular events + on-demand lookups = event trigger is optimal
 * - Visibility: Holistic state + set membership checks = Flow exposure is optimal
 * - Different data structures and access patterns require different architectures
 *
 * **Component Identifier Format:**
 * Components are identified by their full component name string:
 * `"packageName/activityClassName"` (e.g., "com.example.app/.MainActivity")
 *
 * **Data Flow:**
 * 1. User hides/shows app via UI
 * 2. Manager updates DataStore (add/remove from set)
 * 3. DataStore automatically emits new set via `hiddenAppsFlow`
 * 4. `InstalledAppsRepositoryImpl` observes Flow and rebuilds filtered app list
 * 5. UI updates via DiffUtil
 *
 * **Batch Operations:**
 * `updateComponentVisibilities()` allows atomic updates of multiple apps,
 * ensuring consistency and reducing DataStore writes.
 *
 * **Error Handling:**
 * All operations return Boolean success indicators or use default values on failure.
 * IOException from DataStore is caught and results in empty set emission.
 * [java.util.concurrent.CancellationException] is always re-thrown for proper coroutine cancellation.
 *
 * @property dataStore Preferences DataStore for persisting hidden app set
 * @property context Application context for system access
 * @property hiddenAppsFlow Reactive Flow of currently hidden component identifiers
 *
 * @see CustomNamesRepositoryImpl for an example where event-based triggers are more appropriate
 */
@Singleton
class HiddenAppsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context
) : HiddenAppsRepository {

    private object PreferencesKeys {
        val HIDDEN_COMPONENTS = stringSetPreferencesKey("hidden_components_set")
    }

    override val hiddenAppsFlow: Flow<Set<String>>
        get() = dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading hidden components preferences")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { preferences ->
                preferences[PreferencesKeys.HIDDEN_COMPONENTS] ?: emptySet()
            }

    override suspend fun isComponentHidden(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            hiddenAppsFlow.first().contains(componentName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Error checking if component is hidden: $componentName")
            false
        }
    }

    override suspend fun hideComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            val currentHidden = hiddenAppsFlow.first()

            // Bereits versteckt - frühzeitiger Erfolg
            if (currentHidden.contains(componentName)) {
                return true
            }

            dataStore.edit { preferences ->
                val newHidden = currentHidden + componentName
                preferences[PreferencesKeys.HIDDEN_COMPONENTS] = newHidden
            }

            Timber.i("Component hidden: $componentName")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Error hiding component: $componentName")
            false
        }
    }

    override suspend fun showComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            val currentHidden = hiddenAppsFlow.first()

            // Bereits sichtbar - frühzeitiger Erfolg
            if (!currentHidden.contains(componentName)) {
                return true
            }

            dataStore.edit { preferences ->
                val newHidden = currentHidden - componentName
                preferences[PreferencesKeys.HIDDEN_COMPONENTS] = newHidden
            }

            Timber.i("Component shown: $componentName")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Error showing component: $componentName")
            false
        }
    }

    override suspend fun updateComponentVisibilities(
        componentsToHide: Set<String>,
        componentsToShow: Set<String>
    ) {
        try {
            dataStore.edit { preferences ->
                val currentHidden = preferences[PreferencesKeys.HIDDEN_COMPONENTS] ?: emptySet()

                val newHidden = currentHidden.toMutableSet()

                newHidden.addAll(componentsToHide)
                newHidden.removeAll(componentsToShow)

                preferences[PreferencesKeys.HIDDEN_COMPONENTS] = newHidden
            }
            Timber.i("Component visibilities updated. Hidden: ${componentsToHide.size}, Shown: ${componentsToShow.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Error updating component visibilities in batch")
        }
    }

    override suspend fun purgeRepository() {
        try {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.HIDDEN_COMPONENTS] = emptySet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to purge HiddenAppsRepositoryImpl repository")
        }
    }
}