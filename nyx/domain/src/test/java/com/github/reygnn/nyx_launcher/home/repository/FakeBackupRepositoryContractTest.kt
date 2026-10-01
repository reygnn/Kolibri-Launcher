package com.github.reygnn.nyx_launcher.home.repository

/** Runs [BackupRepositoryContract] against the stub; see the contract for why there is no impl run. */
class FakeBackupRepositoryContractTest : BackupRepositoryContract() {
    override fun createRepository(): BackupRepository = FakeBackupRepository()
}
