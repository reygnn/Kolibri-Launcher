package com.github.reygnn.launcher.core.wallpaper

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake
 * [FakeFabPositionRepository]. Siehe [FabPositionRepositoryContract]
 * für die tatsächlichen Tests.
 */
class FakeFabPositionRepositoryContractTest : FabPositionRepositoryContract() {

    override fun createRepository(): FabPositionRepository = FakeFabPositionRepository()
}
