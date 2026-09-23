package com.github.reygnn.launcher.common.data.installedapps

import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.launcher.core.installedapps.InstalledAppsStateRepositoryContract

/** Impl side of the state-holder contract triple: the production holder must obey
 *  the same contract as the fake, incl. last-known-good fallback (SIA-INV-5). */
class InstalledAppsStateRepositoryImplContractTest : InstalledAppsStateRepositoryContract() {
    override fun createRepository(): InstalledAppsStateRepository = InstalledAppsStateRepositoryImpl()
}
