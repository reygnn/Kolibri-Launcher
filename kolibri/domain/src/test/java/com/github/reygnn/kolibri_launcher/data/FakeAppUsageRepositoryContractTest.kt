package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository

/**
 * Contract-Test-Ausführung gegen den Stub-Fake [FakeAppUsageRepository].
 *
 * Siehe [AppUsageRepositoryContract] für die tatsächlichen Tests und für die
 * wichtige Begründung, warum dieser Contract bewusst keine Sortier-Reihenfolge
 * fordert.
 */
class FakeAppUsageRepositoryContractTest : AppUsageRepositoryContract() {

    override fun createRepository(): AppUsageRepository = FakeAppUsageRepository()
}
