package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The drawer reads the SHARED reactive loader via the prime pattern (first non-empty
 * [AppLoad.Loaded]) and projects to LauncherApp, sorting by display name. Custom
 * names are NOT wired here post-migration (the shared [AppInfo] carries none — see
 * the OVERLAY GAP TODO in GetDrawerAppsUseCase); the old Nyx impl already produced
 * `customName = null`, so this is behaviour-preserving, not a regression.
 *
 * Uses a hot [MutableStateFlow]-backed fake (never completes), so the empty/Failed
 * cases exercise the real `withTimeoutOrNull` fallback rather than a completing flow
 * (which would make `.first {}` throw instead of time out).
 */
class GetDrawerAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeSharedLoader(initial: AppLoad) : InstalledAppsRepository {
        val flow = MutableStateFlow(initial)
        override fun getInstalledApps(): Flow<AppLoad> = flow
        override suspend fun triggerAppsUpdate() = Unit
        override suspend fun purgeRepository() = Unit
    }

    private fun appInfo(label: String) = AppInfo(
        originalName = label,
        displayName = label,
        packageName = "pkg.$label",
        className = "pkg.$label.Main",
    )

    @Test
    fun sorts_by_display_name_case_insensitively() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeSharedLoader(AppLoad.Loaded(listOf(appInfo("banana"), appInfo("Apple"), appInfo("cherry"))))
        val result = GetDrawerAppsUseCase(repo, mainDispatcherRule.dispatcher)()
        assertThat(result.map { it.label }).containsExactly("Apple", "banana", "cherry").inOrder()
        // Post-migration projection: the shared AppInfo carries no custom name.
        assertThat(result.all { it.customName == null }).isTrue()
    }

    @Test
    fun failed_load_yields_empty_drawer() = runTest(mainDispatcherRule.dispatcher) {
        // A persistent Failed never satisfies the non-empty-Loaded prime, so the
        // prime times out and the drawer falls back to empty (never throws).
        val repo = FakeSharedLoader(AppLoad.Failed(RuntimeException("enumeration boom")))
        assertThat(GetDrawerAppsUseCase(repo, mainDispatcherRule.dispatcher)()).isEmpty()
    }

    @Test
    fun empty_load_times_out_to_empty_drawer() = runTest(mainDispatcherRule.dispatcher) {
        // [F4] The conflated initial Loaded(emptyList()) never satisfies the prime;
        // it falls through the timeout to an empty drawer — a latency edge, not a hang.
        val repo = FakeSharedLoader(AppLoad.Loaded(emptyList()))
        assertThat(GetDrawerAppsUseCase(repo, mainDispatcherRule.dispatcher)()).isEmpty()
    }
}
