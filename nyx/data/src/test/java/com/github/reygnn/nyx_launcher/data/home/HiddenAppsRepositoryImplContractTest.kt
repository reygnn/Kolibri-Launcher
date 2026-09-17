package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepositoryContract
import kotlinx.coroutines.runBlocking

/**
 * The impl half of the triple (CLAUDE.md rule 2). Runs the SAME
 * [HiddenAppsRepositoryContract] as the fake over the real DataStore-backed impl; if the two
 * diverge in observable behaviour, one goes red. Seeding goes through the real
 * [HiddenAppsRepositoryImpl.update] path (over a [FakeDataStore]) so the "emits the initial
 * value" case also exercises a genuine serialize -> persist -> deserialize round-trip.
 */
class HiddenAppsRepositoryImplContractTest : HiddenAppsRepositoryContract() {
    override fun createRepository(initial: Set<ComponentKey>): HiddenAppsRepository {
        val repository = HiddenAppsRepositoryImpl(FakeDataStore(), HiddenAppsSerializer())
        runBlocking { repository.update { initial } }
        return repository
    }
}
