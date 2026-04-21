package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeBackupRepository

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeBackupRepository].
 *
 * Siehe [BackupRepositoryContract] für die tatsächlichen Tests und für die
 * wichtige Begründung, warum es hier KEINEN Manager-Contract-Test gibt.
 */
class FakeBackupRepositoryContractTest : BackupRepositoryContract() {

    override fun createRepository(): BackupRepository = FakeBackupRepository()
}
