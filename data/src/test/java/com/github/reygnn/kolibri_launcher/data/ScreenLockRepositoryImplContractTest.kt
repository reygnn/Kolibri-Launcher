package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [ScreenLockRepositoryImpl].
 *
 * Setup-Details:
 *   - Kein Konstruktor-Argument nötig — der Manager hat einen `@Inject`-No-Args-
 *     Konstruktor und keine Dependencies (reine In-Memory Event-Bus-Klasse).
 */
class ScreenLockRepositoryImplContractTest : ScreenLockRepositoryContract() {

    override fun createRepository(): ScreenLockRepository = ScreenLockRepositoryImpl()
}
