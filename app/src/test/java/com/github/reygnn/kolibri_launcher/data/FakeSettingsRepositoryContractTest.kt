package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeSettingsRepository].
 *
 * Siehe [SettingsRepositoryContract] für die tatsächlichen Tests.
 */
class FakeSettingsRepositoryContractTest : SettingsRepositoryContract() {

    override fun createRepository(): SettingsRepository = FakeSettingsRepository()
}
