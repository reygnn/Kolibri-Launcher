package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Option A: the drawer reads the shared in-RAM HOLDER (not the loader), projecting to
 * LauncherApp and sorting by display name. The holder's keep-last-good (SIA-INV-5) is
 * the point: a transient empty snapshot after a real load still yields the last-good
 * list, so the drawer never blanks on a reload glitch — the gap the old pull-on-open
 * path had. Custom names are still NOT wired here (the shared [AppInfo] carries none —
 * OVERLAY GAP TODO); the projection produces `customName = null`.
 *
 * Uses a hot [MutableStateFlow]-backed fake holder (never completes), so the empty
 * case exercises the real `withTimeoutOrNull` fallback rather than a completing flow.
 */
class GetDrawerAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Inline holder fake with the same last-good fallback as the impl (SIA-INV-5). */
    private class FakeHolder : InstalledAppsStateRepository {
        private val state = MutableStateFlow<List<AppInfo>>(emptyList())
        private var lastGood: List<AppInfo> = emptyList()
        override val rawAppsFlow: StateFlow<List<AppInfo>> = state
        override fun updateApps(newApps: List<AppInfo>) {
            if (newApps.isNotEmpty()) lastGood = newApps
            state.value = newApps
        }
        override fun getCurrentApps(): List<AppInfo> = state.value.ifEmpty { lastGood }
        override suspend fun purgeRepository() { state.value = emptyList(); lastGood = emptyList() }
    }

    private fun appInfo(label: String) = AppInfo(
        originalName = label,
        displayName = label,
        packageName = "pkg.$label",
        className = "pkg.$label.Main",
    )

    @Test
    fun sorts_by_display_name_case_insensitively() = runTest(mainDispatcherRule.dispatcher) {
        val holder = FakeHolder().apply {
            updateApps(listOf(appInfo("banana"), appInfo("Apple"), appInfo("cherry")))
        }
        val result = GetDrawerAppsUseCase(holder, mainDispatcherRule.dispatcher)()
        assertThat(result.map { it.label }).containsExactly("Apple", "banana", "cherry").inOrder()
        assertThat(result.all { it.customName == null }).isTrue()
    }

    @Test
    fun empty_holder_times_out_to_empty_drawer() = runTest(mainDispatcherRule.dispatcher) {
        // Holder never fed (cold start before the pump lands anything): the non-empty
        // prime never satisfies, times out, getCurrentApps() is empty → empty drawer.
        val holder = FakeHolder()
        assertThat(GetDrawerAppsUseCase(holder, mainDispatcherRule.dispatcher)()).isEmpty()
    }

    @Test
    fun transient_empty_after_a_real_load_still_shows_last_good() = runTest(mainDispatcherRule.dispatcher) {
        // The Option A win: a load lands, then a transient empty snapshot arrives; the
        // drawer must still show the last-good list, not blank.
        val holder = FakeHolder().apply {
            updateApps(listOf(appInfo("Apple"), appInfo("banana")))
            updateApps(emptyList())
        }
        val result = GetDrawerAppsUseCase(holder, mainDispatcherRule.dispatcher)()
        assertThat(result.map { it.label }).containsExactly("Apple", "banana").inOrder()
    }
}
