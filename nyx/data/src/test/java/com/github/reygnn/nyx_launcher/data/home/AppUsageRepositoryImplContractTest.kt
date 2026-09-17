package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepositoryContract

/**
 * The impl half of the triple (Rule 2). Runs the SAME [AppUsageRepositoryContract] as the
 * fake over the real DataStore-backed impl (over a [FakeDataStore]); if the two diverge in
 * observable behaviour, one goes red.
 */
class AppUsageRepositoryImplContractTest : AppUsageRepositoryContract() {
    override fun createRepository(): AppUsageRepository = AppUsageRepositoryImpl(FakeDataStore())
}
