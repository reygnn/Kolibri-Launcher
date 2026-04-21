package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [ScreenLockManager].
 *
 * Setup-Details:
 *   - Kein Konstruktor-Argument nötig — der Manager hat einen `@Inject`-No-Args-
 *     Konstruktor und keine Dependencies (reine In-Memory Event-Bus-Klasse).
 */
class ScreenLockManagerContractTest : ScreenLockRepositoryContract() {

    override fun createRepository(): ScreenLockRepository = ScreenLockManager()
}
