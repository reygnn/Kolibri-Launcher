package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse
 * [FabPositionRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - Constructed directly via the single `@Inject constructor(dataStore)`.
 *     Since the hot-share teardown (DATASTORE_READ_SPEC Belang A), the flow is
 *     cold — there is no `externalScope` / `sharingStrategy` / `createForTesting`
 *     factory to route around, and no stale replay to guard against.
 */
class FabPositionRepositoryImplContractTest : FabPositionRepositoryContract() {

    override fun createRepository(): FabPositionRepository {
        val fakeDataStore = FakeDataStore()
        return FabPositionRepositoryImpl(dataStore = fakeDataStore)
    }
}
