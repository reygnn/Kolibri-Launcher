package com.github.reygnn.nyx_launcher.home.repository

import app.cash.turbine.test
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test

/**
 * The behavioural contract every [DrawerFoldersRepository] must satisfy — the
 * abstract half of the triple (CLAUDE.md rule 2). `FakeDrawerFoldersRepositoryContractTest`
 * and (in `:data`) `DrawerFoldersRepositoryImplContractTest` extend it and only supply
 * [createRepository]; if the fake and the impl drift, one side goes red.
 */
abstract class DrawerFoldersRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Provide a fresh repository whose current state is [initial]. */
    abstract fun createRepository(initial: DrawerFolders): DrawerFoldersRepository

    @Test
    fun folders_emits_the_initial_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(WITH_FOLDER)
        repo.folders().test {
            assertThat(awaitItem()).isEqualTo(WITH_FOLDER)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_persists_the_transformed_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(DrawerFolders.EMPTY)
        repo.folders().test {
            assertThat(awaitItem()).isEqualTo(DrawerFolders.EMPTY)
            repo.update { WITH_FOLDER }
            assertThat(awaitItem()).isEqualTo(WITH_FOLDER)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_returning_null_does_not_write() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(WITH_FOLDER)
        repo.folders().test {
            assertThat(awaitItem()).isEqualTo(WITH_FOLDER)
            repo.update { null }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_receives_the_current_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(WITH_FOLDER)
        var seen: DrawerFolders? = null
        repo.update { current ->
            seen = current
            DrawerFolders.EMPTY
        }
        assertThat(seen).isEqualTo(WITH_FOLDER)
        assertThat(repo.folders().first()).isEqualTo(DrawerFolders.EMPTY)
    }

    /**
     * Two concurrent [DrawerFoldersRepository.update] calls, each appending a folder,
     * must not lose a write (A1-03). The `yield()` inside each transform forces an
     * interleave: with a non-atomic read-modify-write both read the same start (0
     * folders) and the final size is 1; serializing the whole transform under a lock
     * makes it 2. Goes red on any repository whose `update` does not hold across the
     * transform.
     */
    @Test
    fun concurrent_updates_do_not_lose_writes() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(DrawerFolders.EMPTY)
        val append: suspend (DrawerFolders) -> DrawerFolders = { current ->
            yield()
            val i = current.folders.size
            current.copy(
                folders = current.folders + DrawerFolder(DrawerFolderId("f$i"), "", listOf(key("x"), key("y"))),
            )
        }
        val a = launch { repo.update(append) }
        val b = launch { repo.update(append) }
        a.join()
        b.join()
        assertThat(repo.folders().first().folders).hasSize(2)
    }

    private companion object {
        private fun key(p: String) = ComponentKey(p, "$p.Main")
        val WITH_FOLDER = DrawerFolders(
            listOf(DrawerFolder(DrawerFolderId("f1"), "", listOf(key("a"), key("b")))),
        )
    }
}
