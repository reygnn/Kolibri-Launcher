package com.github.reygnn.nyx_launcher.home.repository

/** Runs [ResetRepositoryContract] against the stub; see the contract for why there is no impl run. */
class FakeResetRepositoryContractTest : ResetRepositoryContract() {
    override fun createRepository(): ResetRepository = FakeResetRepository()
}
