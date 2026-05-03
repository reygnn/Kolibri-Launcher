package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import kotlinx.coroutines.flow.SharingStarted

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [SwipeActionsRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - `externalScope = null` umgeht den `shareIn`-Layer aus den im
 *     [FavoritesRepositoryImplContractTest] dokumentierten Gründen
 *     (Write-then-Read-Sequenzen unter `UnconfinedTestDispatcher` würden
 *     sonst stale Replay-Werte sehen).
 *   - Konstruiert über `createForTesting()`, weil der primäre Konstruktor
 *     `private` ist.
 */
class SwipeActionsRepositoryImplContractTest : SwipeActionsRepositoryContract() {

    override fun createRepository(): SwipeActionsRepository {
        val fakeDataStore = FakeDataStore()
        return SwipeActionsRepositoryImpl.createForTesting(
            dataStore = fakeDataStore,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )
    }
}
