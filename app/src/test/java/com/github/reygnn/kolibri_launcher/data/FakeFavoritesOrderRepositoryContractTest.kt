package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeFavoritesOrderRepository].
 *
 * Siehe [FavoritesOrderRepositoryContract] für die tatsächlichen Tests.
 */
class FakeFavoritesOrderRepositoryContractTest : FavoritesOrderRepositoryContract() {

    override fun createRepository(): FavoritesOrderRepository = FakeFavoritesOrderRepository()
}
