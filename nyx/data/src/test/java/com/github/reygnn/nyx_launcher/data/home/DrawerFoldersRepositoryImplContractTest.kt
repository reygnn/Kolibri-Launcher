package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepositoryContract
import kotlinx.coroutines.runBlocking

/**
 * The impl half of the triple (CLAUDE.md rule 2). Runs the SAME
 * [DrawerFoldersRepositoryContract] as the fake over the real DataStore-backed impl;
 * if the two diverge in observable behaviour, one goes red. Seeding goes through the
 * real [DrawerFoldersRepositoryImpl.update] path (over a [FakeDataStore]) so the
 * "emits the initial value" case also exercises a genuine serialize → persist →
 * deserialize round-trip.
 */
class DrawerFoldersRepositoryImplContractTest : DrawerFoldersRepositoryContract() {
    override fun createRepository(initial: DrawerFolders): DrawerFoldersRepository {
        val repository = DrawerFoldersRepositoryImpl(FakeDataStore(), DrawerFoldersSerializer())
        runBlocking { repository.update { initial } }
        return repository
    }
}
