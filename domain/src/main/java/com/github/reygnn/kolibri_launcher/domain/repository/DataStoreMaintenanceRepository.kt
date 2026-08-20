package com.github.reygnn.kolibri_launcher.domain.repository

/**
 * User-triggered housekeeping for the **settings** DataStore: removes orphaned keys — keys written by
 * an older app version whose feature has since been removed or moved to another store, and which
 * nothing reads or writes anymore.
 *
 * Driven by a keep-list, not a retired-list: every live owner declares the settings-store keys it
 * uses via [com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys], and this deletes every
 * settings-store key that NO owner claims (a blacklist-of-unknown). A retired key is cleaned the
 * moment its owner is gone — nothing to hand-maintain. This is GC of dead data, not a data migration
 * (Rule 5): it bridges nothing and runs only when the user asks (never on launch). The inverted
 * failure mode (a forgotten owner could delete a live key) is guarded by the impl's empty-keep-list
 * floor and the `checkConventions` settings-key linter.
 */
interface DataStoreMaintenanceRepository {

    /**
     * Removes every retired key currently in the settings store.
     *
     * Reports the outcome as a value instead of collapsing an I/O failure into a plausible
     * "nothing to clean" (Rule 11 / AUDIT-10: a caught failure must stay a failure — a swallowed
     * write error must not masquerade as success). Cancellation always propagates.
     */
    suspend fun removeOrphanKeys(): Result

    /** Outcome of [removeOrphanKeys]. */
    sealed interface Result {
        /** The edit succeeded; [count] retired keys were removed (`0` = the store was already clean). */
        data class Removed(val count: Int) : Result

        /** The edit failed (I/O error); nothing was removed and orphans may still be present. */
        data object Failed : Result
    }
}
