package com.github.reygnn.kolibri_launcher.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
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
 * This manager uses a sophisticated Flow setup optimized for production and testing;
 * the shared read/share plumbing lives in [SharedDataStoreFlowRepository]:
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
 * **Reconcile Mechanism:**
 * `reconcileFavoriteComponents()` removes favorites for uninstalled apps by:
 * - Computing orphans (favorites absent from the loaded app list)
 * - Gating each orphan through the injected presence predicate; a still-present
 *   one is vetoed (kept) — RECONCILE_FIX_SPEC R-INV-2
 * - Reading candidate and deleting from the SAME fail-closed store read (a read
 *   error propagates and deletes nothing), and removing value-scoped in `edit{}`
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
 * `reconcileFavoriteComponents()` is deliberately fail-closed: a read/edit error
 * propagates (no swallow) so the caller's runCleanup skips it and deletes nothing.
 *
 * @property dataStore Preferences DataStore for persisting favorites set
 * @property externalScope Application scope for hot Flow sharing (null in tests)
 * @property favoriteComponentsFlow Hot shared Flow of currently favorited component identifiers
 *
 * @see HiddenAppsRepositoryImpl for similar Flow-based state management pattern
 * @see CustomNamesRepositoryImpl for contrast with event-based architecture
 */
@Singleton
class FavoritesRepositoryImpl
@VisibleForTesting
constructor(
    dataStore: DataStore<Preferences>,
    externalScope: CoroutineScope?,
    sharingStrategy: SharingStarted,
) : SharedDataStoreFlowRepository(dataStore, externalScope, sharingStrategy),
    FavoritesRepository {

    private object PreferencesKeys {
        val FAVORITES = stringSetPreferencesKey("favorites_components_set")
    }

    /**
     * Primärer Konstruktor für Dagger/Hilt.
     */
    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        @ApplicationScope externalScope: CoroutineScope?
    ) : this(
        dataStore = dataStore,
        externalScope = externalScope,
        sharingStrategy = SharingStarted.Companion.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
    )

    override val favoriteComponentsFlow: Flow<Set<String>> =
        sharedReadFlow("Error reading favorites preferences") { preferences ->
            preferences[PreferencesKeys.FAVORITES] ?: emptySet()
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

                if (currentFavoritePackages.size >= AppConstants.MAX_FAVORITES_ON_HOME) {
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
            // stale-replay ok: called from the Home path (AppManagementDelegate),
            // where GetFavoriteAppsUseCase keeps a warm subscriber on this hot
            // flow, so the replay cache is current — not a cold cross-Activity read.
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

    override suspend fun getFavoriteComponentsSnapshot(): Set<String> {
        return try {
            // Authoritative FRESH read (not the hot favoriteComponentsFlow replay
            // cache): the backup export runs while Home holds no subscriber, so a
            // .first() on the shared flow could return a stale replayed set.
            dataStore.data.first()[PreferencesKeys.FAVORITES] ?: emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Fail-open to empty on a transient read error — non-destructive: a
            // backup that can't read favorites records empty rather than crashing
            // the user-initiated export. Matches the flow's RELEASE behavior
            // (safeReadFlow → empty on IOException). Deliberately Timber.w, NOT
            // silentError: an IOException here is a real I/O failure, not a
            // programmer error, so it must not throw in DEBUG — this is a conscious
            // divergence from the flow (whose safeReadFlow DOES throw in DEBUG via
            // silentError), so a DEBUG backup doesn't crash on a store hiccup.
            Timber.w(e, "Error reading favorites snapshot; treating as empty")
            emptySet()
        }
    }

    override suspend fun reconcileFavoriteComponents(
        installedComponentNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    ) {
        // FAIL-CLOSED read (propagates IOException; deliberately NOT the fail-open
        // shared flow / safeReadFlow): candidate read and delete are the same
        // authority (RECONCILE_FIX_SPEC R-INV-2). No try/catch here — errors
        // propagate to the caller's runCleanup, which skips this store.
        val current = dataStore.data.first()[PreferencesKeys.FAVORITES] ?: emptySet()
        val orphans = current - installedComponentNames.toSet()
        if (orphans.isEmpty()) return

        // Gate every candidate through PackagePresence; a present one is vetoed.
        val verifiedAbsent = orphans.filterNotTo(HashSet()) { isStillPresent(it) }
        if (verifiedAbsent.isEmpty()) return

        dataStore.edit { preferences ->
            // Value-scoped: re-read inside the edit and subtract the verified-absent
            // set, so a concurrently-added favorite survives.
            val now = preferences[PreferencesKeys.FAVORITES] ?: return@edit
            val cleaned = now - verifiedAbsent
            if (cleaned.size < now.size) {
                Timber.w("Removed ${now.size - cleaned.size} orphaned favorites")
                preferences[PreferencesKeys.FAVORITES] = cleaned
            }
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("FavoritesRepositoryImpl") { preferences ->
            preferences[PreferencesKeys.FAVORITES] = emptySet()
        }
    }
}
