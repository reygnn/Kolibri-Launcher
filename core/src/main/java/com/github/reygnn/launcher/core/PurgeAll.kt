package com.github.reygnn.launcher.core

import kotlinx.coroutines.CancellationException

/**
 * Purges [stores] one after the other, each in isolation — the factory reset of both apps
 * (Kolibri's `ResetRepositoryImpl` steps, Nyx's `ResetRepositoryImpl.factoryReset`). Shared
 * since the 2b cleanup; the `ResetCompletenessContract` pins the result in both apps.
 *
 * A failing store is logged and counted, never stopping the rest; a cancellation propagates
 * unchanged. Returns true only if every purge succeeded.
 *
 * DEBUG builds: the failure is logged with [TimberWrapper.silentError], which by the house rule
 * THROWS in DEBUG (crash loudly in development, keep running in release). So in a DEBUG build the
 * first failing store breaks out of here: the isolation, a `false` result — and with it Kolibri's
 * `PartialFailure` and Nyx's "reset incomplete" message — apply in release builds and in unit
 * tests, where the DEBUG throw is off. To provoke an incomplete reset on a device, use a release
 * build.
 *
 * @param stores display name → store, in purge order.
 */
suspend fun purgeAll(stores: List<Pair<String, Purgeable>>): Boolean {
    var allSuccessful = true
    for ((name, store) in stores) {
        try {
            store.purgeRepository()
            KolibriLog.d("$name purged successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): one failing store must not stop the
            // others; OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Error purging $name")
            allSuccessful = false
        }
    }
    return allSuccessful
}
