package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for favorite apps with reactive Flow-based architecture and package limits.
 *
 * This singleton manages which apps are marked as favorites (pinned to home screen)
 * by persisting component identifiers in DataStore and exposing them as a hot,
 * shared Flow. It enforces a package-based limit to prevent home screen overcrowding
 * while allowing multiple activities from the same package.
 *
 * **Core Functionality:**
 * - Add/remove/toggle favorite apps by component name
 * - Check if specific app is favorited
 * - Batch save favorite components
 * - Cleanup orphaned favorites (from uninstalled apps)
 * - Enforce package limit for favorites
 * - Expose complete favorites set reactively via hot Flow
 *
 * **Architecture: Hot Shared Flow with Dual Constructor Pattern**
 *
 * This manager uses a sophisticated Flow setup optimized for production and testing:
 *
 * **Production (Primary Constructor):**
 * - Uses `shareIn()` with `WhileSubscribed(5000)` for hot sharing
 * - Single shared subscription to DataStore across all collectors
 * - 5-second replay timeout prevents unnecessary DataStore reads
 * - Optimized for multiple UI collectors (HomeScreen, BottomSheet, etc.)
 *
 * **Testing (Secondary Constructor):**
 * - Accepts custom `SharingStarted` strategy parameter
 * - Tests can use `SharingStarted.Eagerly` for synchronous behavior
 * - Marked `@VisibleForTesting` to signal test-only usage
 *
 * **Why Flow-Based (Like AppVisibilityManager)?**
 * - Favorites are holistic STATE (complete set needed for home screen)
 * - Multiple UI components observe the same favorites simultaneously
 * - Set membership checks are O(1) and efficient
 * - DataStore naturally provides Flow—preserve that reactivity
 * - Unlike `AppNamesManager` (granular events), this is perfect for Flow
 *
 * **Package-Based Limit Enforcement:**
 * The manager enforces `MAX_FAVORITES_ON_HOME` as a **package limit**, not a
 * component limit. This design allows:
 * - Multiple activities from the same package (e.g., Gmail + Gmail Compose)
 * - Prevents bloat from too many different apps
 * - `addFavoriteComponent()` checks unique package count before adding new packages
 *
 * **Component Identifier Format:**
 * Components are identified by their full component name string:
 * `"packageName/activityClassName"` (e.g., "com.android.chrome/.MainActivity")
 *
 * **Cleanup Mechanism:**
 * `cleanupFavoriteComponents()` removes favorites for uninstalled apps by:
 * - Taking intersection with currently installed components
 * - Logging removal count for debugging
 * - Preserving backup in debug builds
 * - Failing gracefully on errors (keeps current state)
 *
 * **Data Flow:**
 * 1. User toggles favorite via UI
 * 2. Manager updates DataStore (add/remove from set)
 * 3. DataStore emits new set via `favoriteComponentsFlow`
 * 4. All observers receive update simultaneously (hot sharing)
 * 5. UI updates via DiffUtil
 *
 * **Error Handling:**
 * All operations return Boolean success indicators or use default values on failure.
 * IOException from DataStore is caught and results in empty set emission.
 * [java.util.concurrent.CancellationException] is always re-thrown for proper coroutine cancellation.
 * Cleanup failures preserve current state rather than clearing favorites.
 *
 * @property dataStore Preferences DataStore for persisting favorites set
 * @property context Application context for system access
 * @property externalScope Application scope for hot Flow sharing (null in tests)
 * @property favoriteComponentsFlow Hot shared Flow of currently favorited component identifiers
 *
 * @see HiddenAppsRepositoryImpl for similar Flow-based state management pattern
 * @see CustomNamesRepositoryImpl for contrast with event-based architecture
 */
@Singleton
class FavoritesRepositoryImpl : FavoritesRepository {

    private val dataStore: DataStore<Preferences>
    private val context: Context
    private val externalScope: CoroutineScope?
    override val favoriteComponentsFlow: Flow<Set<String>>

    private object PreferencesKeys {
        val FAVORITES = stringSetPreferencesKey("favorites_components_set")
    }

    /**
     * Primärer Konstruktor für Dagger/Hilt.
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
        sharingStrategy = SharingStarted.Companion.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
    )

    /**
     * Sekundärer, interner Konstruktor für Tests.
     */
    @VisibleForTesting
    constructor(
        dataStore: DataStore<Preferences>,
        context: Context,
        externalScope: CoroutineScope?,
        sharingStrategy: SharingStarted
    ) {
        this.dataStore = dataStore
        this.context = context
        this.externalScope = externalScope
        this.favoriteComponentsFlow = initializeFlow(sharingStrategy)
    }

    private fun initializeFlow(sharingStrategy: SharingStarted): Flow<Set<String>> {
        return dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading favorites preferences")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { preferences ->
                preferences[PreferencesKeys.FAVORITES] ?: emptySet()
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

    override suspend fun toggleFavoriteComponent(componentName: String): Boolean {
        return try {
            val isCurrentlyFavorite = isFavoriteComponent(componentName)
            if (isCurrentlyFavorite) {
                removeFavoriteComponent(componentName)
                false
            } else {
                addFavoriteComponent(componentName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error toggling favorite component: $componentName")
            false
        }
    }

    override suspend fun addFavoriteComponent(componentName: String): Boolean {
        if (componentName.isBlank()) return false

        return try {
            // Read-modify-write fully inside the edit transaction: reading the
            // current set outside edit (via flow.first()) races a concurrent
            // cleanup/add — a stale snapshot would clobber the other change.
            var success = true
            dataStore.edit { preferences ->
                val currentFavorites = preferences[PreferencesKeys.FAVORITES] ?: emptySet()

                if (currentFavorites.contains(componentName)) {
                    return@edit
                }

                val currentFavoritePackages = currentFavorites.map { it.split('/')[0] }.toSet()

                if (currentFavoritePackages.size >= AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME) {
                    val newPackageName = componentName.split('/')[0]
                    if (!currentFavoritePackages.contains(newPackageName)) {
                        Timber.w("Favorites limit reached. Cannot add component from new package: $componentName")
                        success = false
                        return@edit
                    }
                }

                preferences[PreferencesKeys.FAVORITES] = currentFavorites + componentName
            }
            success

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding favorite component: $componentName")
            false
        }
    }

    override suspend fun removeFavoriteComponent(componentName: String): Boolean {
        if (componentName.isBlank()) return false

        return try {
            // Read-modify-write inside the edit transaction so a concurrent
            // add/cleanup cannot be clobbered by a stale outside snapshot.
            dataStore.edit { preferences ->
                val currentFavorites = preferences[PreferencesKeys.FAVORITES] ?: emptySet()
                if (currentFavorites.contains(componentName)) {
                    preferences[PreferencesKeys.FAVORITES] = currentFavorites - componentName
                }
            }
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error removing favorite component: $componentName")
            false
        }
    }

    override suspend fun isFavoriteComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            favoriteComponentsFlow.first().contains(componentName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error checking if component is favorite: $componentName")
            false
        }
    }

    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        try {
            val filtered = componentNames.filter { it.isNotBlank() }.toSet()
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.FAVORITES] = filtered
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error saving favorite components")
        }
    }

    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {
        try {
            dataStore.edit { preferences ->
                val currentFavorites = preferences[PreferencesKeys.FAVORITES] ?: emptySet()
                if (currentFavorites.isEmpty()) return@edit

                val installedSet = installedComponentNames.toSet()
                val cleanedFavorites = currentFavorites.intersect(installedSet)

                if (cleanedFavorites.size < currentFavorites.size) {
                    val removedCount = currentFavorites.size - cleanedFavorites.size
                    Timber.w("Removed $removedCount invalid favorites")

                    if (TimberWrapper.isDebugBuild) {
                        Timber.d("Backup favorites before cleanup: $currentFavorites")
                    }

                    preferences[PreferencesKeys.FAVORITES] = cleanedFavorites
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to cleanup favorites, keeping current state")
            // Nicht crashen, einfach den aktuellen Zustand behalten
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("FavoritesRepositoryImpl") { preferences ->
            preferences[PreferencesKeys.FAVORITES] = emptySet()
        }
    }
}