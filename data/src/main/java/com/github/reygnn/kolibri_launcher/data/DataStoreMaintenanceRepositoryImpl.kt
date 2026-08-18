package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.core.IoDispatcher
import com.github.reygnn.kolibri_launcher.core.RetiredDataStoreKeys
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
 * the usage store (live data) are out of scope. Deletes only [RetiredDataStoreKeys] entries, so a
 * live key is never touched.
 */
@Singleton
class DataStoreMaintenanceRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DataStoreMaintenanceRepository {

    override suspend fun scanOrphanKeys(): List<String> = withContext(ioDispatcher) {
        try {
            dataStore.data.first().asMap().keys
                .map { it.name }
                .filter { RetiredDataStoreKeys.isRetiredSettingsKey(it) }
                .sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // DataStore read is an I/O boundary; a scan failure is non-fatal — report nothing to
            // clean rather than surfacing an error to the user.
            TimberWrapper.silentError(e, "Failed to scan settings store for orphan keys")
            emptyList()
        }
    }

    override suspend fun removeOrphanKeys(): Int = withContext(ioDispatcher) {
        try {
            var removed = 0
            dataStore.edit { preferences ->
                // Collect first, then remove — never mutate while iterating the key view.
                val toRemove = preferences.asMap().keys
                    .filter { RetiredDataStoreKeys.isRetiredSettingsKey(it.name) }
                toRemove.forEach { preferences.remove(it) }
                removed = toRemove.size
            }
            removed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to remove orphan keys from settings store")
            0
        }
    }
}
