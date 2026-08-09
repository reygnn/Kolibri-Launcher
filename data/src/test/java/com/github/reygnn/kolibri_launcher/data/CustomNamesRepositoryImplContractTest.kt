package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [CustomNamesRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] as the DataStore double. It is the only constructor
 *     argument since the `appsUpdateTrigger` event bus and
 *     `triggerCustomNameUpdate` were removed (REACTIVE_APPLIST_SPEC): a rename
 *     is now purely reactive via `customNamesFlow` (DataStore re-emission).
 */
class CustomNamesRepositoryImplContractTest : CustomNamesRepositoryContract() {

    override fun createRepository(): CustomNamesRepository {
        return CustomNamesRepositoryImpl(FakeDataStore())
    }
}

