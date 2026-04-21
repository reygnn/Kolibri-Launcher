package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeHiddenAppsRepository].
 *
 * Siehe [HiddenAppsRepositoryContract] für die tatsächlichen Tests.
 */
class FakeHiddenAppsRepositoryContractTest : HiddenAppsRepositoryContract() {

    override fun createRepository(): HiddenAppsRepository = FakeHiddenAppsRepository()
}
