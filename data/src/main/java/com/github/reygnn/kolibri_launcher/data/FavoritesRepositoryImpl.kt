package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.ComponentKey
import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for favorite apps: which apps are pinned to the home screen, by
 * component identifier (`"packageName/activityClassName"`), persisted in
 * DataStore and enforced against a package-based limit.
 *
 * **Cold read + snapshot, one authoritative path (DATASTORE_READ_SPEC Belang A).**
 * [favoriteComponentsFlow] is a plain cold flow via [readFlowFailOpen];
 * [getFavoriteComponentsSnapshot] is a fresh point-read via [snapshotFailOpen].
 * Both run the SAME transform (`prefs[FAVORITES] ?: emptySet()`) over the store,
 * so they cannot drift (DSR-INV-1), and there is no `shareIn(replay=1)` cache to
 * go stale — the AUDIT-13 hazard is gone by construction. The constructor takes
 * just the [DataStore]; no `externalScope` / `sharingStrategy` / test factory.
 *
 * **Three read policies, all via named envelopes (DSR-INV-3):**
 * - continuous / point-read for DISPLAY → fail-open ([favoriteComponentsFlow],
 *   [getFavoriteComponentsSnapshot], [isFavoriteComponent]): an I/O error yields
 *   the empty default, never throws.
 * - the reconcile candidate read → fail-CLOSED ([snapshotFailClosed]): an I/O
 *   error propagates so a transient failure deletes nothing (RECONCILE_FIX_SPEC
 *   R-INV-2).
 * - every WRITE (add/remove/save/reconcile-delete) is a read-modify-write INSIDE
 *   `edit{}`, so a concurrent change can't be clobbered by a stale outside
 *   snapshot.
 *
 * **Package-based limit.** `MAX_FAVORITES_ON_HOME` caps distinct PACKAGES, not
 * components, so multiple activities of one app (Gmail + Gmail Compose) are
 * allowed while bloat from too many different apps is prevented.
 *
 * **Error handling.** Operations return Boolean success or a default on failure;
 * [java.util.concurrent.CancellationException] is always re-thrown.
 *
 * @see FavoritesOrderRepositoryImpl for the ORDER of favorites (how they sort)
 */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : FavoritesRepository, OwnsSettingsStoreKeys {

    private object PreferencesKeys {
        val FAVORITES = stringSetPreferencesKey("favorites_components_set")
    }

    override fun ownedExactKeys(): Set<String> = setOf(PreferencesKeys.FAVORITES.name)

    // distinctUntilChanged: this key lives in the shared settingsDataStore, so
    // DataStore.data re-emits on EVERY write to that store (usage, sort order,
    // custom names, …), not just favorites edits. Deduping here stops those
    // unrelated writes from re-triggering the favorites/drawer combines with an
    // identical Set. Mirrors customNamesFlow / usageFlow (AUDIT-14 F1c/F2).
    override val favoriteComponentsFlow: Flow<Set<String>> =
        dataStore.readFlowFailOpen("Error reading favorites preferences") { preferences ->
            preferences[PreferencesKeys.FAVORITES] ?: emptySet()
        }
            .distinctUntilChanged()

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
        if (!ComponentKey.isValid(componentName)) {
            // A malformed key (not a package/class flatten) is a programmer error
            // on the call path, not user data — surface it loudly in DEBUG (Rule 9)
            // and no-op in release, rather than persisting a stale entry that the
            // backup restore would later drop silently (TODO §15).
            TimberWrapper.silentError(
                "addFavoriteComponent: malformed component key '$componentName' (expected package/class)"
            )
            return false
        }

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

                val currentFavoritePackages = currentFavorites.map { it.substringBefore('/') }.toSet()

                if (currentFavoritePackages.size >= AppConstants.MAX_FAVORITES_ON_HOME) {
                    val newPackageName = componentName.substringBefore('/')
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
            // Cold flow (DATASTORE_READ_SPEC Belang A): favoriteComponentsFlow.first()
            // is a fresh read of dataStore.data, so this is always current — no
            // replay cache, no warm-subscriber assumption needed.
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

    override suspend fun getFavoriteComponentsSnapshot(): Set<String> =
        // Authoritative FRESH read via snapshotFailOpen: same transform as the cold
        // favoriteComponentsFlow (DSR-INV-1), fail-open to empty on IOException.
        // Used by point-reads from a context without a warm Home subscriber
        // (backup export, Settings sort, Onboarding edit).
        dataStore.snapshotFailOpen("Error reading favorites snapshot; treating as empty") { preferences ->
            preferences[PreferencesKeys.FAVORITES] ?: emptySet()
        }

    override suspend fun readFavoritesForEdit(): FavoritesEditRead =
        try {
            // Fail-CLOSED, DISTINGUISHABLE read (DSR-INV-4): returns Unavailable on
            // I/O rather than an empty Loaded, so the editor save-gate can block a
            // wipe. Fresh point-read, same FAVORITES key as the flow/snapshot.
            // CancellationException is rethrown FIRST (DSR-INV-5) — this file is not
            // on cancel_files, so the arm is hand-maintained; scanCancelCandidates is
            // the backstop.
            FavoritesEditRead.Loaded(dataStore.data.first()[PreferencesKeys.FAVORITES] ?: emptySet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "Error reading favorites for edit; reporting Unavailable")
            FavoritesEditRead.Unavailable(e)
        }

    override suspend fun reconcileFavoriteComponents(
        installedComponentNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    ) {
        // FAIL-CLOSED read via snapshotFailClosed (propagates IOException;
        // deliberately NOT the fail-open cold flow / snapshotFailOpen): the
        // candidate read and the delete are the same authority
        // (RECONCILE_FIX_SPEC R-INV-2). No try/catch here — errors propagate to
        // the caller's runCleanup, which skips this store.
        val current = dataStore.snapshotFailClosed { it[PreferencesKeys.FAVORITES] ?: emptySet() }
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
