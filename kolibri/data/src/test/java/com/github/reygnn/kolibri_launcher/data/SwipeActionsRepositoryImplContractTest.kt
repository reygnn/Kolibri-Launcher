package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [SwipeActionsRepositoryImpl].
 *
 * Setup: [FakeDataStore] als DataStore-Double. The impl reads authoritatively
 * from the store on every call (no hot/shared flow anymore), so there is no
 * `shareIn` layer to bypass and no external scope to wire.
 */
class SwipeActionsRepositoryImplContractTest : SwipeActionsRepositoryContract() {

    override fun createRepository(): SwipeActionsRepository =
        SwipeActionsRepositoryImpl(FakeDataStore())
}
