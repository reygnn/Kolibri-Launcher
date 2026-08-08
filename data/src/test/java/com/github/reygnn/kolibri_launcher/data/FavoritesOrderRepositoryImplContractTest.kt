package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [FavoritesOrderRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - Constructed directly via the single `@Inject constructor(dataStore)`.
 *     Since the hot-share teardown (DATASTORE_READ_SPEC Belang A) the flow is
 *     cold — no `externalScope` / `sharingStrategy` / `createForTesting` factory
 *     to route around, and no stale replay to guard against.
 */
class FavoritesOrderRepositoryImplContractTest : FavoritesOrderRepositoryContract() {

    override fun createRepository(): FavoritesOrderRepository {
        val fakeDataStore = FakeDataStore()
        return FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)
    }
}
