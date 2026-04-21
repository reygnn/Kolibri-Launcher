package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeCustomNamesRepository].
 *
 * Siehe [CustomNamesRepositoryContract] für die tatsächlichen Tests.
 */
class FakeCustomNamesRepositoryContractTest : CustomNamesRepositoryContract() {

    override fun createRepository(): CustomNamesRepository = FakeCustomNamesRepository()
}
