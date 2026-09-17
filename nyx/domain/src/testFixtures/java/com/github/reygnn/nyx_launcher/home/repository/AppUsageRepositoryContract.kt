package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The behavioural contract every [AppUsageRepository] must satisfy — the abstract half of
 * the triple (Rule 2). `FakeAppUsageRepositoryContractTest` and (in `:data`)
 * `AppUsageRepositoryImplContractTest` extend it and only supply [createRepository]; if the
 * fake and the impl drift, one side goes red.
 *
 * Like kolibri's usage contract, the EXACT [AppUsageRepository.scoreApps] ordering is not
 * pinned here (it depends on the current time); only its structural properties are.
 */
abstract class AppUsageRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Provide a fresh, empty repository. */
    abstract fun createRepository(): AppUsageRepository

    private fun app(pkg: String) = LauncherApp(ComponentKey(pkg, "$pkg.Main"), pkg, null)

    @Test
    fun record_makes_the_package_appear_in_the_snapshot() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        assertThat(repo.usageSnapshotFlow.first()).isEmpty()

        repo.recordPackageLaunch("com.a")

        val snapshot = repo.usageSnapshotFlow.first()
        assertThat(snapshot.keys).containsExactly("com.a")
        assertThat(snapshot.getValue("com.a")).hasSize(1)
    }

    @Test
    fun blank_or_null_record_is_a_no_op() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.recordPackageLaunch(null)
        repo.recordPackageLaunch("")
        repo.recordPackageLaunch("   ")
        assertThat(repo.usageSnapshotFlow.first()).isEmpty()
    }

    @Test
    fun purge_clears_all_usage() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.recordPackageLaunch("com.a")
        repo.recordPackageLaunch("com.b")
        assertThat(repo.usageSnapshotFlow.first()).isNotEmpty()

        repo.purgeRepository()

        assertThat(repo.usageSnapshotFlow.first()).isEmpty()
    }

    @Test
    fun scoreApps_scores_every_app_and_zeroes_the_unused() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        val a = app("com.a"); val b = app("com.b")

        val scores = repo.scoreApps(listOf(a, b), emptyMap())

        assertThat(scores.keys).containsExactly(a.key, b.key)
        assertThat(scores.getValue(a.key)).isEqualTo(0.0)
        assertThat(scores.getValue(b.key)).isEqualTo(0.0)
    }

    @Test
    fun scoreApps_ranks_a_recently_launched_app_above_an_unused_one() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        val a = app("com.a"); val b = app("com.b")
        repo.recordPackageLaunch("com.a")
        val snapshot = repo.usageSnapshotFlow.first()

        val scores = repo.scoreApps(listOf(a, b), snapshot)

        assertThat(scores.getValue(a.key)).isGreaterThan(scores.getValue(b.key))
        assertThat(scores.getValue(b.key)).isEqualTo(0.0)
    }
}
