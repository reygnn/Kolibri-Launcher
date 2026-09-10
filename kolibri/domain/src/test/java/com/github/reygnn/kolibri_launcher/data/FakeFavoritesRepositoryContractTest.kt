package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeFavoritesRepository].
 *
 * Siehe [FavoritesRepositoryContract] für die tatsächlichen Tests.
 */
class FakeFavoritesRepositoryContractTest : FavoritesRepositoryContract() {

    override fun createRepository(): FavoritesRepository = FakeFavoritesRepository()
}
