package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [InstalledAppsStateRepositoryImpl].
 *
 * Setup-Details:
 *   - Kein Konstruktor-Argument nötig — der Manager hat einen `@Inject`-No-Args-
 *     Konstruktor und keine Dependencies (er ist reiner In-Memory-State-Holder).
 *   - Kein DataStore, kein Context, keine Mocks. Sauberster Manager-Contract-
 *     Test im ganzen Projekt.
 */
class InstalledAppsStateRepositoryImplContractTest : InstalledAppsStateRepositoryContract() {

    override fun createRepository(): InstalledAppsStateRepository =
        InstalledAppsStateRepositoryImpl()
}
