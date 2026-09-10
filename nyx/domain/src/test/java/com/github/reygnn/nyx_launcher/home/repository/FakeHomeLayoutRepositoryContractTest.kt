package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.HomeLayout

/** The fake half of the triple: runs the shared contract against the Fake. */
class FakeHomeLayoutRepositoryContractTest : HomeLayoutRepositoryContract() {
    override fun createRepository(initial: HomeLayout): HomeLayoutRepository =
        FakeHomeLayoutRepository(initial)
}
