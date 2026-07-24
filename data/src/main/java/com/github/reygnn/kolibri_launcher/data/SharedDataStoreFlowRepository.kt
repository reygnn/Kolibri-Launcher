package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map

/**
 * Base for the DataStore-backed repositories that expose their state as a
 * **hot, shared** `Flow` (AUDIT-7 #2). It hoists the constructor plumbing
 * (`dataStore` / `externalScope` / `sharingStrategy`) and the read-flow
 * assembly that four repositories previously copied verbatim.
 *
 * Subclasses keep their own three-part constructor shape — a private (or
 * `@VisibleForTesting`) primary constructor taking a custom [SharingStarted],
 * plus an `@Inject` secondary constructor that defaults it to
 * `WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)` — because the
 * `@Inject`/`createForTesting` entry points differ per repository and the
 * contract tests construct them directly. Only the shared *fields* and the
 * flow-assembly helper live here.
 *
 * Repositories that do **not** hot-share (no `externalScope`: e.g.
 * `HiddenAppsRepositoryImpl`, `WallpaperRepositoryImpl`) do not extend this;
 * they call [safeReadFlow] directly.
 *
 * @property dataStore the backing preferences store, exposed to subclasses
 *   for their `edit`/`safePurge` write paths.
 * @param externalScope application scope for hot sharing; `null` in tests
 *   turns [sharedReadFlow] into a cold flow (see [shareInOrRaw]). Private —
 *   the sharing decision stays encapsulated in [sharedReadFlow].
 * @param sharingStrategy the `WhileSubscribed`/`Eagerly`/… strategy passed
 *   through to `shareIn`. Private for the same reason as [externalScope].
 */
abstract class SharedDataStoreFlowRepository(
    protected val dataStore: DataStore<Preferences>,
    private val externalScope: CoroutineScope?,
    private val sharingStrategy: SharingStarted,
) {

    /**
     * Assembles a hot, shared read flow: recover read errors to empty prefs
     * ([safeReadFlow]), project each snapshot via [transform], then hot-share
     * with this repository's [externalScope]/[sharingStrategy]
     * ([shareInOrRaw]). [errorMessage] labels the `silentError` on IO failure.
     */
    protected fun <T> sharedReadFlow(
        errorMessage: String,
        transform: suspend (Preferences) -> T,
    ): Flow<T> =
        dataStore.safeReadFlow(errorMessage)
            .map(transform)
            .shareInOrRaw(externalScope, sharingStrategy)
}
