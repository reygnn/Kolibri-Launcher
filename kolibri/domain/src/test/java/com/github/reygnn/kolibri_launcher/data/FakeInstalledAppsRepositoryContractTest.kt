package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository

/**
 * Contract-Test-Ausführung gegen den universellen Fake
 * [FakeInstalledAppsRepository].
 *
 * Siehe [InstalledAppsRepositoryContract] für die tatsächlichen Tests und für
 * die Begründung, warum dieser Contract bewusst dünn ist.
 */
class FakeInstalledAppsRepositoryContractTest : InstalledAppsRepositoryContract() {

    override fun createRepository(): InstalledAppsRepository = FakeInstalledAppsRepository()
}
