package com.github.reygnn.launcher.core.installedapps

import com.github.reygnn.launcher.core.InstalledAppsStateRepository

/** Fake side of the state-holder contract triple (MONOREPO_MERGE_SPEC §7). */
class FakeInstalledAppsStateRepositoryContractTest : InstalledAppsStateRepositoryContract() {
    override fun createRepository(): InstalledAppsStateRepository = FakeInstalledAppsStateRepository()
}
