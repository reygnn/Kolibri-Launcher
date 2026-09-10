package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeSwipeActionsRepository].
 *
 * Siehe [SwipeActionsRepositoryContract] für die tatsächlichen Tests.
 */
class FakeSwipeActionsRepositoryContractTest : SwipeActionsRepositoryContract() {

    override fun createRepository(): SwipeActionsRepository = FakeSwipeActionsRepository()
}
