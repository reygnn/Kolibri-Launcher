package com.github.reygnn.nyx_launcher.home.repository

/** Runs [AppUsageRepositoryContract] against the in-memory fake. */
class FakeAppUsageRepositoryContractTest : AppUsageRepositoryContract() {
    override fun createRepository(): AppUsageRepository = FakeAppUsageRepository()
}
