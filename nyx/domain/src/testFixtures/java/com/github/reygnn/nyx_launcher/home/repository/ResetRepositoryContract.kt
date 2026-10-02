package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Contract for [ResetRepository] — deliberately thin, fake-only (like [BackupRepositoryContract]).
 *
 * NO IMPL CONTRACT TEST (ADR) — marker read by `./gradlew checkConventions`
 * (tools/check-contract-triple.sh); exempts the impl half only. The fake is a configurable stub
 * with no logic that could drift from the implementation, and the implementation
 * (`ResetRepositoryImpl` in `:nyx:data`) purges real stores. Its behaviour is covered where that
 * is honest: the shared `ResetCompletenessContract` (`NyxResetCompletenessTest`: every store
 * empty, no wallpaper file) and `ResetRepositoryImplTest` (per-store isolation, a failure is
 * reported, the seed-flag guarantee of R2).
 *
 * What this pins anyway: a reset returns without throwing on a default repository.
 */
abstract class ResetRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected abstract fun createRepository(): ResetRepository

    @Test
    fun factoryReset_returns_without_throwing() = runTest(mainDispatcherRule.testDispatcher) {
        createRepository().factoryReset()
    }
}
