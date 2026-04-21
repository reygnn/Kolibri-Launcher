package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [InstalledAppsStateManager].
 *
 * Setup-Details:
 *   - Kein Konstruktor-Argument nötig — der Manager hat einen `@Inject`-No-Args-
 *     Konstruktor und keine Dependencies (er ist reiner In-Memory-State-Holder).
 *   - Kein DataStore, kein Context, keine Mocks. Sauberster Manager-Contract-
 *     Test im ganzen Projekt.
 */
class InstalledAppsStateManagerContractTest : InstalledAppsStateRepositoryContract() {

    override fun createRepository(): InstalledAppsStateRepository =
        InstalledAppsStateManager()
}
