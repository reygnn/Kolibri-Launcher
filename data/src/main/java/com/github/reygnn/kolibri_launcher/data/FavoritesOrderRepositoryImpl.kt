package com.github.reygnn.kolibri_launcher.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceAtMostSafe
import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.json.JSONArray
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for custom ordering of favorite apps with JSON persistence and hot Flow.
 *
 * This singleton manages the display order of favorited apps on the home screen,
 * persisting the order as a JSON array in DataStore and exposing it as a hot,
 * shared Flow. It works in tandem with `FavoritesRepositoryImpl` to provide both the
 * set of favorites (what) and their order (how).
 *
 * **Core Functionality:**
 * - Save custom order of favorite components
 * - Sort favorite apps according to saved order
 * - Remove components from order when unfavorited
 * - Expose complete order list reactively via hot Flow
 * - Enforce size limits to prevent storage/performance issues
 *
 * **Architecture: Hot Shared Flow with Factory Pattern**
 *
 * Similar to `FavoritesRepositoryImpl`, this uses a sophisticated dual-constructor pattern:
 *
 * **Production (Primary Constructor via @Inject):**
 * - Uses `shareIn()` with `WhileSubscribed(5000)` for hot sharing
 * - Single shared subscription to DataStore across collectors
 * - 5-second replay timeout optimizes repeated accesses
 * - Multiple UI components observe same order simultaneously
 *
 * **Testing (Factory Method):**
 * - `createForTesting()` accepts custom `SharingStarted` strategy
 * - Private primary constructor prevents direct instantiation
 * - Marked `@VisibleForTesting` and uses `internal` visibility
 * - Tests can use `SharingStarted.Eagerly` for synchronous behavior
 *
 * **Why Flow-Based?**
 * - Order is holistic STATE (complete list needed for sorting)
 * - Multiple collectors observe same order (HomeScreen, DragDrop UI)
 * - Natural fit with DataStore's reactive nature
 * - Consistent with `FavoritesRepositoryImpl` and `AppVisibilityManager` patterns
 *
 * **JSON Persistence Strategy:**
 * Order is stored as a JSON array string for several reasons:
 * - DataStore Preferences doesn't support List<String> natively
 * - JSON is compact, human-readable, and well-supported
 * - Easy to debug in DataStore inspector
 * - Simple serialization/deserialization with JSONArray
 *
 * **Size Limit Protection:**
 * Enforces `MAX_ORDER_LIST_SIZE`, a defensive ceiling of 3002 entries derived
 * from the favorites cap (`MAX_FAVORITES_ON_HOME` × `AVG_COMPONENTS_PER_PACKAGE`
 * + `SAFETY_BUFFER`). Not an expected size — real favorite counts are far lower;
 * it only guards against bugs producing runaway lists:
 * - Excessive storage usage from bugs
 * - Performance degradation in JSON parsing
 * - Memory issues from accidentally huge lists
 * Limits are applied both on save and load for defense-in-depth.
 *
 * **Sorting Algorithm:**
 * `sortAppsWithGivenOrder()` implements a two-phase sort:
 * 1. **Ordered phase**: Apps in saved order appear first, in specified sequence
 * 2. **Alphabetical phase**: Remaining apps appended alphabetically
 *
 * This ensures:
 * - User's custom order is preserved for favorited apps
 * - Newly favorited apps (not yet in order) appear alphabetically at end
 * - Graceful handling of order list containing unfavorited apps
 *
 * **Integration with FavoritesRepositoryImpl:**
 * - `FavoritesRepositoryImpl` determines WHICH apps are favorites (Set<String>)
 * - `FavoritesOrderRepositoryImpl` determines HOW they are ordered (List<String>)
 * - Separation of concerns: membership vs. sequence
 * - Order list may contain extra entries (cleaned up lazily)
 *
 * **Component Identifier Format:**
 * Components are identified by their full component name string:
 * `"packageName/activityClassName"` (e.g., "com.google.android.gm/.ConversationListActivity")
 *
 * **Data Flow:**
 * 1. User drags to reorder favorites in UI
 * 2. UI calls `saveOrder()` with new sequence
 * 3. Manager persists JSON to DataStore
 * 4. DataStore emits new list via `favoriteComponentsOrderFlow`
 * 5. HomeScreen observes and re-sorts displayed favorites
 * 6. UI updates via DiffUtil with smooth animations
 *
 * **Error Handling:**
 * - JSON parsing errors result in empty list fallback
 * - Sorting failures cascade to alphabetical fallback, then unsorted fallback
 * - Save failures return false but don't crash
 * - IOException from DataStore caught and results in empty list
 * - [java.util.concurrent.CancellationException] always re-thrown for proper coroutine cancellation
 * - Multiple fallback layers ensure UI never breaks
 *
 * @property dataStore Preferences DataStore for persisting order as JSON
 * @property externalScope Application scope for hot Flow sharing (null in tests)
 * @property favoriteComponentsOrderFlow Hot shared Flow of ordered component identifiers
 *
 * @see FavoritesRepositoryImpl for favorite membership management (what is favorited)
 * @see HiddenAppsRepositoryImpl for similar Flow-based state management pattern
 */
@Singleton
open class FavoritesOrderRepositoryImpl private constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val externalScope: CoroutineScope?,
    sharingStrategy: SharingStarted
) : FavoritesOrderRepository {

    override val favoriteComponentsOrderFlow: Flow<List<String>> = initializeFlow(sharingStrategy)

    private object PreferencesKeys {
        val ORDER_LIST = stringPreferencesKey("favorites_order_components_list_json")
    }

    companion object {
        /**
         * Maximum size of the order list as a safety limit.
         *
         * Defensive upper bound derived from the favorites cap: at most
         * MAX_FAVORITES_ON_HOME (500) favorited packages, allowing up to
         * AVG_COMPONENTS_PER_PACKAGE (6) component entries each, plus a small
         * buffer — i.e. 500 × 6 + 2 = 3002 entries. Not an expected size (real
         * favorite counts are far lower); purely a ceiling that guards against
         * bugs producing runaway lists (storage bloat, JSON-parse cost, OOM).
         */
        private const val AVG_COMPONENTS_PER_PACKAGE = 6
        private const val SAFETY_BUFFER = 2
        private const val MAX_ORDER_LIST_SIZE =
            AppConstants.MAX_FAVORITES_ON_HOME * AVG_COMPONENTS_PER_PACKAGE + SAFETY_BUFFER


        @VisibleForTesting
        fun createForTesting(
            dataStore: DataStore<Preferences>,
            externalScope: CoroutineScope?,
            sharingStrategy: SharingStarted
        ): FavoritesOrderRepositoryImpl {
            return FavoritesOrderRepositoryImpl(dataStore, externalScope, sharingStrategy)
        }
    }

    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        @ApplicationScope externalScope: CoroutineScope?
    ) : this(
        dataStore = dataStore,
        externalScope = externalScope,
        sharingStrategy = SharingStarted.Companion.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
    )

    private fun initializeFlow(sharingStrategy: SharingStarted): Flow<List<String>> {
        return dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading favorites order")
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
                        // Mit Limit für Sicherheit
                        val size = jsonArray.length().coerceAtMostSafe(MAX_ORDER_LIST_SIZE)
                        List(size) { i -> jsonArray.getString(i) }
                    } catch (e: JSONException) {
                        TimberWrapper.silentError(e, "Error parsing favorites order JSON")
                        emptyList()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Unexpected error parsing order")
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
            // Limitierung für Sicherheit
            val limitedList = orderedComponentNames.take(MAX_ORDER_LIST_SIZE)

            val jsonArray = JSONArray(limitedList)
            val orderString = jsonArray.toString()

            dataStore.edit { preferences ->
                preferences[PreferencesKeys.ORDER_LIST] = orderString
            }

            Timber.d("Favorites order saved: ${limitedList.size} components")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving favorites order")
            false
        }
    }

    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>): List<AppInfo> {
        if (favoriteApps.isEmpty()) return emptyList()

        return try {
            sortAppsWithGivenOrder(favoriteApps, order)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error sorting favorite components, falling back to alphabetical")
            try {
                favoriteApps.sortedBy { it.displayName.lowercase() }
            } catch (e2: Throwable) {
                TimberWrapper.silentError(e2, "Critical error in fallback sorting, returning unsorted list")
                favoriteApps
            }
        }
    }

    fun sortAppsWithGivenOrder(appsToSort: List<AppInfo>, order: List<String>): List<AppInfo> {
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

        } catch (e: Throwable) {
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
                true // Bereits nicht vorhanden
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing component from order: $componentName")
            false
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("FavoritesOrderRepositoryImpl") { preferences ->
            preferences.remove(PreferencesKeys.ORDER_LIST)
        }
    }
}