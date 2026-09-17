package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * First-run seeding ([DrawerFoldersRepositoryImpl.seedInitialFolders]). Like the home
 * dock seed, this is only meaningful over a DataStore that has never been written, so
 * it's pinned here against a fresh [FakeDataStore] rather than in the shared contract.
 */
class DrawerFoldersRepositoryImplSeedTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun newRepo() = DrawerFoldersRepositoryImpl(FakeDataStore(), DrawerFoldersSerializer())

    @Test
    fun seeds_the_folder_on_a_fresh_store() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()

        val seeded = repo.seedInitialFolders { listOf(googleFolder(GMAIL, MAPS)) }

        assertThat(seeded).isTrue()
        val folders = repo.folders().first().folders
        assertThat(folders.map { it.title }).containsExactly("Google")
        assertThat(folders.single().members).containsExactly(GMAIL, MAPS).inOrder()
    }

    @Test
    fun drops_a_folder_below_the_two_member_invariant() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()

        val seeded = repo.seedInitialFolders { listOf(googleFolder(GMAIL)) }

        assertThat(seeded).isFalse()
        assertThat(repo.folders().first().folders).isEmpty()
    }

    @Test
    fun seeds_only_once() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        assertThat(repo.seedInitialFolders { listOf(googleFolder(GMAIL, MAPS)) }).isTrue()

        // A returning launch — even one where the user deleted every folder — must not re-seed.
        repo.update { DrawerFolders.EMPTY }
        val second = repo.seedInitialFolders { listOf(googleFolder(GMAIL, MAPS)) }

        assertThat(second).isFalse()
        assertThat(repo.folders().first().folders).isEmpty()
    }

    @Test
    fun does_not_seed_when_folders_already_exist() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        repo.update { DrawerFolders(listOf(googleFolder(GMAIL, MAPS))) }

        var resolved = false
        val seeded = repo.seedInitialFolders { resolved = true; listOf(googleFolder(GMAIL, MAPS)) }

        assertThat(seeded).isFalse()
        // Folders were established (e.g. an import) — the resolver must not even run.
        assertThat(resolved).isFalse()
    }

    @Test
    fun does_not_resolve_on_a_returning_install() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        assertThat(repo.seedInitialFolders { listOf(googleFolder(GMAIL, MAPS)) }).isTrue()

        var resolved = false
        val second = repo.seedInitialFolders { resolved = true; listOf(googleFolder(GMAIL, MAPS)) }

        assertThat(second).isFalse()
        assertThat(resolved).isFalse()
    }

    @Test
    fun seed_is_skipped_when_the_store_read_throws() = runTest(mainDispatcherRule.dispatcher) {
        // Contained fail-closed: an IOException on the seed read is caught → no seed, no crash.
        val throwing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("boom") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                emptyPreferences()
        }
        val repo = DrawerFoldersRepositoryImpl(throwing, DrawerFoldersSerializer())

        val seeded = repo.seedInitialFolders { listOf(googleFolder(GMAIL, MAPS)) }

        assertThat(seeded).isFalse()
    }

    private companion object {
        val GMAIL = ComponentKey("com.google.android.gm", "com.google.android.gm.Main")
        val MAPS = ComponentKey("com.google.android.apps.maps", "com.google.android.maps.Main")

        fun googleFolder(vararg members: ComponentKey) =
            DrawerFolder(DrawerFolderId("google"), title = "Google", members = members.toList())
    }
}
