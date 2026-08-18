package com.github.reygnn.kolibri_launcher.domain.repository

/**
 * User-triggered housekeeping for the **settings** DataStore: finds and removes orphaned keys —
 * keys written by an older app version whose feature has since been removed or moved to another
 * store, and which nothing reads or writes anymore.
 *
 * Safe by construction: it only ever touches keys listed in
 * [com.github.reygnn.kolibri_launcher.core.RetiredDataStoreKeys] (a whitelist of RETIRED keys), so a
 * live key is never deleted. This is GC of dead data, not a data migration (Rule 5): it bridges
 * nothing, drops only provably-dead keys, and runs only when the user asks (never on launch).
 */
interface DataStoreMaintenanceRepository {

    /** Names of the retired (orphaned) keys currently present in the settings store. Empty = clean. */
    suspend fun scanOrphanKeys(): List<String>

    /** Removes every retired key currently in the settings store; returns how many were removed. */
    suspend fun removeOrphanKeys(): Int
}
