package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFabPositionRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake
 * [FakeFabPositionRepository]. Siehe [FabPositionRepositoryContract]
 * für die tatsächlichen Tests.
 */
class FakeFabPositionRepositoryContractTest : FabPositionRepositoryContract() {

    override fun createRepository(): FabPositionRepository = FakeFabPositionRepository()
}
