package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeScreenLockRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeScreenLockRepository].
 *
 * Siehe [ScreenLockRepositoryContract] für die tatsächlichen Tests.
 */
class FakeScreenLockRepositoryContractTest : ScreenLockRepositoryContract() {

    override fun createRepository(): ScreenLockRepository = FakeScreenLockRepository()
}
