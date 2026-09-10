package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepositoryContract
import kotlinx.coroutines.runBlocking
/**
 * The impl half of the triple (CLAUDE.md rule 2). Runs the SAME
 * `HomeLayoutRepositoryContract` as the fake; if the real DataStore-backed impl
 * and the fake ever diverge in observable behaviour, one of them goes red.
 *
 * Seeding goes through the real [HomeLayoutRepositoryImpl.save] path (over a
 * [FakeDataStore]) so the contract's "emits the initial value" also exercises a
 * genuine serialize → persist → deserialize round-trip.
 */
class HomeLayoutRepositoryImplContractTest : HomeLayoutRepositoryContract() {

    override fun createRepository(initial: HomeLayout): HomeLayoutRepository {
        val repository = HomeLayoutRepositoryImpl(FakeDataStore(), HomeLayoutSerializer())
        runBlocking { repository.save(initial) }
        return repository
    }
}
