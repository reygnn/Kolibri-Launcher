package com.github.reygnn.launcher.feature.wallpaper

import com.github.reygnn.launcher.core.wallpaper.FabPositionRepository
import com.github.reygnn.launcher.core.wallpaper.FabPositionRepositoryContract

/**
 * Contract run against the production class
 * [FabPositionStore] (was Kolibri's `FabPositionRepositoryImpl`, moved with its cases in 3a-5).
 *
 * Setup:
 *   - [InMemoryPreferencesStore] as the DataStore double.
 *   - Constructed directly via the single `@Inject constructor(dataStore)`.
 *     Since the hot-share teardown (DATASTORE_READ_SPEC Belang A), the flow is
 *     cold — there is no `externalScope` / `sharingStrategy` / `createForTesting`
 *     factory to route around, and no stale replay to guard against.
 */
class FabPositionStoreContractTest : FabPositionRepositoryContract() {

    override fun createRepository(): FabPositionRepository {
        return FabPositionStore(dataStore = InMemoryPreferencesStore())
    }
}
