package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.core.IoDispatcher
import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings-store housekeeping (see [DataStoreMaintenanceRepository]). Injects the UNQUALIFIED
 * settings [DataStore] — the only store where orphans accumulate; the consent store (privacy) and
 * the usage store (self-healing live data) are out of scope.
 *
 * **Blacklist-of-unknown, driven by the live keep-list.** [keyOwners] is the multibound set of every
 * [OwnsSettingsStoreKeys] component; the keep-list (exact names + prefixes) is rebuilt from them on
 * every run, straight from the live `Preferences.Key` objects each owner declares. Any key in the
 * store that no owner claims is a retired orphan and is removed — so a key stops being cleaned the
 * moment its owner is deleted, with no hand-maintained retired-key list to update.
 *
 * **Failure mode is inverted vs. the old whitelist, and guarded accordingly.** With a
 * whitelist-of-dead a forgotten entry left a harmless orphan; here a forgotten OWNER would make a
 * live key look orphaned and get deleted. That is contained by (1) owners returning keys from the
 * live key objects (nothing to keep in sync), (2) `@IntoSet` auto-registration (the aggregation site
 * can't miss an owner), (3) the `checkConventions` settings-key linter, and (4) the empty-keep-list
 * guard below (a DI failure must never be read as "the store has no live keys").
 */
@Singleton
class DataStoreMaintenanceRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val keyOwners: Set<@JvmSuppressWildcards OwnsSettingsStoreKeys>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DataStoreMaintenanceRepository {

    override suspend fun removeOrphanKeys(): DataStoreMaintenanceRepository.Result =
        withContext(ioDispatcher) {
            try {
                // Rebuild the keep-list from the live owners each run.
                val keptExact = keyOwners.flatMapTo(HashSet()) { it.ownedExactKeys() }
                val keptPrefixes = keyOwners.flatMapTo(HashSet()) { it.ownedKeyPrefixes() }

                // Defensive floor: a blacklist that deletes "everything not kept" is only safe while
                // the keep-list is populated. An empty exact-key set can only mean a DI/multibinding
                // failure (production always has 8+ owners), never a legitimately empty store — so
                // refuse rather than wipe every settings key. Reports Failed (never Removed), and
                // silentError makes the wiring bug loud in DEBUG.
                if (keptExact.isEmpty()) {
                    TimberWrapper.silentError(
                        IllegalStateException("Settings-store keep-list is empty; refusing to purge"),
                        "removeOrphanKeys aborted: empty keep-list (DI wiring failure?)",
                    )
                    return@withContext DataStoreMaintenanceRepository.Result.Failed
                }

                var removed = 0
                dataStore.edit { preferences ->
                    // Collect first, then remove — never mutate while iterating the key view.
                    val toRemove = preferences.asMap().keys.filter { key ->
                        key.name !in keptExact && keptPrefixes.none { key.name.startsWith(it) }
                    }
                    toRemove.forEach { preferences.remove(it) }
                    removed = toRemove.size
                }
                DataStoreMaintenanceRepository.Result.Removed(removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A write failure must stay a failure (Rule 11 / AUDIT-10): report Failed so the UI
                // can tell the user, never collapse it to Removed(0) which reads as "already clean".
                TimberWrapper.silentError(e, "Failed to remove orphan keys from settings store")
                DataStoreMaintenanceRepository.Result.Failed
            }
        }
}
