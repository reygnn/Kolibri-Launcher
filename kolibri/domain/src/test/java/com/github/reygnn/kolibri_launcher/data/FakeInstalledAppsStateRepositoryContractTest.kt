package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeInstalledAppsStateRepository].
 *
 * Siehe [InstalledAppsStateRepositoryContract] für die tatsächlichen Tests.
 */
class FakeInstalledAppsStateRepositoryContractTest : InstalledAppsStateRepositoryContract() {

    override fun createRepository(): InstalledAppsStateRepository =
        FakeInstalledAppsStateRepository()
}
