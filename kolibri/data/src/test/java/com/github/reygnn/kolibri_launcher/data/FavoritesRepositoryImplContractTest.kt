package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [FavoritesRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double (das echte DataStore braucht
 *     Android-Dateisystem — für Unit-Tests nicht nutzbar).
 *   - Constructed directly via the single `@Inject constructor(dataStore)`.
 *     Since the hot-share teardown (DATASTORE_READ_SPEC Belang A) the flow is
 *     cold, so every `.first()` on `favoriteComponentsFlow` is already a fresh
 *     read of `dataStore.data` — no `externalScope` / `sharingStrategy` to route
 *     around, and no stale replay to guard against.
 */
class FavoritesRepositoryImplContractTest : FavoritesRepositoryContract() {

    override fun createRepository(): FavoritesRepository {
        val fakeDataStore = FakeDataStore()
        return FavoritesRepositoryImpl(dataStore = fakeDataStore)
    }
}
