package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey

/** Runs [HiddenAppsRepositoryContract] against the in-memory fake. */
class FakeHiddenAppsRepositoryContractTest : HiddenAppsRepositoryContract() {
    override fun createRepository(initial: Set<ComponentKey>): HiddenAppsRepository =
        FakeHiddenAppsRepository(initial)
}
