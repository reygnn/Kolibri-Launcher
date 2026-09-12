package com.github.reygnn.nyx_launcher.home.repository

/** The fake half of the triple: runs the shared contract against the Fake. */
class FakePreferencesRepositoryContractTest : PreferencesRepositoryContract() {
    override fun createRepository(): PreferencesRepository = FakePreferencesRepository()
}
