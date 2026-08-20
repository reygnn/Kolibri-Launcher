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
import kotlinx.coroutines.flow.first
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
 * store that no owner claims is a retired orphan. [removeOrphanKeys] deletes them inside `edit {}`;
 * [previewOrphanKeys] computes the identical set from a read-only snapshot for the dry-run UI.
 *
 * **Failure mode is inverted vs. the old whitelist, and guarded accordingly.** With a
 * whitelist-of-dead a forgotten entry left a harmless orphan; here a forgotten OWNER would make a
 * live key look orphaned and get deleted. That is contained by (1) owners returning keys from the
 * live key objects (nothing to keep in sync), (2) the `checkConventions` settings-key linter —
 * per-owner key completeness AND owner↔`@IntoSet` binding parity, since the `@IntoSet` binding is a
 * manual per-owner line, not automatic, so a forgotten binding fails the build rather than silently
 * dropping the owner from this set — and (3) the empty-keep-list guard in [liveKeepList] (a total DI
 * failure must never be read as "the store has no live keys").
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
                val keep = liveKeepList()
                    ?: return@withContext DataStoreMaintenanceRepository.Result.Failed

                var removed = 0
                dataStore.edit { preferences ->
                    // Collect first, then remove — never mutate while iterating the key view.
                    val toRemove = keep.orphanKeysIn(preferences)
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

    override suspend fun previewOrphanKeys(): DataStoreMaintenanceRepository.PreviewResult =
        withContext(ioDispatcher) {
            try {
                val keep = liveKeepList()
                    ?: return@withContext DataStoreMaintenanceRepository.PreviewResult.Failed

                // Read-only snapshot — no edit{}, so the dry run deletes nothing.
                val preferences = dataStore.data.first()
                val names = keep.orphanKeysIn(preferences).map { it.name }.sorted()
                DataStoreMaintenanceRepository.PreviewResult.Loaded(names)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A read failure must stay a failure (Rule 11 / AUDIT-10): report Failed so the UI
                // never shows an empty "nothing to clean" list that hides orphans it couldn't read.
                TimberWrapper.silentError(e, "Failed to preview orphan keys in settings store")
                DataStoreMaintenanceRepository.PreviewResult.Failed
            }
        }

    /**
     * The live keep-list (exact key names + prefixes each owner currently claims), or `null` when it
     * is empty. An empty exact-key set can only mean a DI/multibinding failure — production always
     * has 8+ owners — never a legitimately empty store, so both public methods treat `null` as a
     * refusal (report Failed) rather than "delete everything not kept". [TimberWrapper.silentError]
     * makes the wiring bug loud in DEBUG.
     */
    private fun liveKeepList(): KeepList? {
        val exact = keyOwners.flatMapTo(HashSet()) { it.ownedExactKeys() }
        if (exact.isEmpty()) {
            TimberWrapper.silentError(
                IllegalStateException("Settings-store keep-list is empty; refusing to touch the store"),
                "Settings-store keep-list is empty (DI wiring failure?)",
            )
            return null
        }
        val prefixes = keyOwners.flatMapTo(HashSet()) { it.ownedKeyPrefixes() }
        return KeepList(exact, prefixes)
    }

    /** A resolved keep-list; [orphanKeysIn] is the single filter shared by remove and preview. */
    private class KeepList(private val exact: Set<String>, private val prefixes: Set<String>) {
        fun orphanKeysIn(preferences: Preferences): List<Preferences.Key<*>> =
            preferences.asMap().keys.filter { key ->
                key.name !in exact && prefixes.none { key.name.startsWith(it) }
            }
    }
}
