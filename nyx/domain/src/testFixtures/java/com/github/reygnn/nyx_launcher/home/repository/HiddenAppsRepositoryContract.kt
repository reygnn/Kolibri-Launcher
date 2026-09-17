package com.github.reygnn.nyx_launcher.home.repository

import app.cash.turbine.test
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test

/**
 * The behavioural contract every [HiddenAppsRepository] must satisfy — the abstract half
 * of the triple (CLAUDE.md rule 2). `FakeHiddenAppsRepositoryContractTest` and (in `:data`)
 * `HiddenAppsRepositoryImplContractTest` extend it and only supply [createRepository]; if
 * the fake and the impl drift, one side goes red.
 */
abstract class HiddenAppsRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Provide a fresh repository whose current state is [initial]. */
    abstract fun createRepository(initial: Set<ComponentKey>): HiddenAppsRepository

    @Test
    fun hidden_emits_the_initial_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(setOf(A, B))
        repo.hidden().test {
            assertThat(awaitItem()).isEqualTo(setOf(A, B))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_persists_the_transformed_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(emptySet())
        repo.hidden().test {
            assertThat(awaitItem()).isEmpty()
            repo.update { it + A }
            assertThat(awaitItem()).isEqualTo(setOf(A))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_returning_null_does_not_write() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(setOf(A))
        repo.hidden().test {
            assertThat(awaitItem()).isEqualTo(setOf(A))
            repo.update { null }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_receives_the_current_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(setOf(A))
        var seen: Set<ComponentKey>? = null
        repo.update { current ->
            seen = current
            current + B
        }
        assertThat(seen).isEqualTo(setOf(A))
        assertThat(repo.hidden().first()).isEqualTo(setOf(A, B))
    }

    /**
     * Two concurrent [HiddenAppsRepository.update] calls, each adding a distinct key, must
     * not lose a write (A1-03). The `yield()` inside each transform forces an interleave:
     * with a non-atomic read-modify-write both read the same start and the final size is 1;
     * serializing the whole transform under a lock makes it 2.
     */
    @Test
    fun concurrent_updates_do_not_lose_writes() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(emptySet())
        val a = launch { repo.update { yield(); it + A } }
        val b = launch { repo.update { yield(); it + B } }
        a.join()
        b.join()
        assertThat(repo.hidden().first()).isEqualTo(setOf(A, B))
    }

    private companion object {
        val A = ComponentKey("com.a", "com.a.Main")
        val B = ComponentKey("com.b", "com.b.Main")
    }
}
