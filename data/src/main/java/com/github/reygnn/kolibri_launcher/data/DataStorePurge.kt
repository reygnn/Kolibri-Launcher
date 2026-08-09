package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException

/**
 * Purges DataStore-backed repository state with the standard Kolibri
 * try/catch envelope: rethrows `CancellationException`, routes any other
 * `Throwable` to `silentError`. The lambda runs inside the DataStore
 * transaction so callers can use `preferences[KEY] = …`,
 * `preferences.remove(KEY)`, or filter `preferences.asMap().keys`
 * exactly as if they had called `dataStore.edit { … }` directly.
 *
 * Final log message on failure: `"Failed to purge $repoName repository"`.
 *
 * Repositories whose state lives outside DataStore (system APIs, pure
 * runtime state) implement `purgeRepository()` as a documented no-op and
 * never reach this helper. Repositories whose `purgeRepository` body has
 * post-edit ordering requirements (a follow-up step that must run only after
 * a successful edit, never after a silent edit failure) keep their inline
 * try/catch shape — the helper would change the commit-then-follow-up
 * semantics.
 *
 * Introduced for AUDIT.md §8.7 to consolidate the six purgeRepository
 * implementations that share this exact body.
 */
internal suspend fun DataStore<Preferences>.safePurge(
    repoName: String,
    block: suspend (MutablePreferences) -> Unit
) {
    try {
        edit(block)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Failed to purge $repoName repository")
    }
}
