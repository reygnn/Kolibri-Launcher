package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.DrawerFolders

/** Runs [DrawerFoldersRepositoryContract] against the in-memory fake. */
class FakeDrawerFoldersRepositoryContractTest : DrawerFoldersRepositoryContract() {
    override fun createRepository(initial: DrawerFolders): DrawerFoldersRepository =
        FakeDrawerFoldersRepository(initial)
}
