package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepositoryContract

/**
 * The impl half of the triple (A1-16): runs the SAME PreferencesRepositoryContract
 * as the fake over a real DataStore-backed [PreferencesRepositoryImpl]. If the
 * fake and the impl ever diverge in observable behaviour, one goes red.
 */
class PreferencesRepositoryImplContractTest : PreferencesRepositoryContract() {
    override fun createRepository(): PreferencesRepository =
        PreferencesRepositoryImpl(FakeDataStore())
}
