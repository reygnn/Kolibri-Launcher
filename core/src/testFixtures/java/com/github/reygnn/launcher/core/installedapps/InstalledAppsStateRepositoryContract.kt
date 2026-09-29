package com.github.reygnn.launcher.core.installedapps

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test

/**
 * CONTRACT TEST for [InstalledAppsStateRepository] — migrated into `:core`
 * testFixtures so the impl (`:common-data`) and the fake ([FakeInstalledAppsStateRepository])
 * are pinned by ONE contract (Contract-Triple-Regel, MONOREPO_MERGE_SPEC §7).
 *
 * The interface looks trivial but carries one property that MUST be pinned:
 * **last-known-good caching** (SIA-INV-5). `getCurrentApps()` falls back to the
 * last non-empty list when the live state is empty, so a transient enumeration
 * hiccup never surfaces an empty drawer. Whoever removes that fallback in a
 * refactor is caught here.
 *
 * Deliberate drifts (NOT in contract): `purgeRepository()` (impl no-op vs. fake
 * clears — Test-Isolation) and the concrete `rawAppsFlow` backing.
 *
 * Uses [MainDispatcherRule] with a single `StandardTestDispatcher` (family
 * convention: one dispatcher source, no ad-hoc TestScope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class InstalledAppsStateRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected abstract fun createRepository(): InstalledAppsStateRepository

    private fun appInfo(name: String, pkg: String) = AppInfo(
        originalName = name,
        displayName = name,
        packageName = pkg,
        className = "$pkg.MainActivity",
    )

    private val appA = appInfo("Alpha", "com.example.a")
    private val appB = appInfo("Beta", "com.example.b")
    private val appC = appInfo("Gamma", "com.example.c")

    // ---------- Initial state ----------

    @Test
    fun `fresh repository emits empty list on rawAppsFlow`() {
        assertThat(createRepository().rawAppsFlow.value).isEqualTo(emptyList<AppInfo>())
    }

    @Test
    fun `fresh repository returns empty from getCurrentApps`() {
        assertThat(createRepository().getCurrentApps()).isEqualTo(emptyList<AppInfo>())
    }

    // ---------- updateApps + rawAppsFlow ----------

    @Test
    fun `updateApps reflects in rawAppsFlow value`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        assertThat(repo.rawAppsFlow.value).isEqualTo(listOf(appA, appB))
    }

    @Test
    fun `updateApps reflects in getCurrentApps`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        assertThat(repo.getCurrentApps()).isEqualTo(listOf(appA, appB))
    }

    @Test
    fun `updateApps preserves input order`() {
        val repo = createRepository()
        val input = listOf(appC, appA, appB) // deliberately non-alphabetical
        repo.updateApps(input)
        assertThat(repo.getCurrentApps()).isEqualTo(input)
    }

    @Test
    fun `updateApps overwrites previous state`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(listOf(appB, appC))
        assertThat(repo.getCurrentApps()).isEqualTo(listOf(appB, appC))
    }

    // ---------- Last-known-good (SIA-INV-5) — the important property ----------

    @Test
    fun `getCurrentApps falls back to last non-empty list when state is empty`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        repo.updateApps(emptyList())

        // Direct flow read sees the explicit "empty" state.
        assertThat(repo.rawAppsFlow.value).isEqualTo(emptyList<AppInfo>())
        // getCurrentApps falls back to last known good.
        assertThat(repo.getCurrentApps()).isEqualTo(listOf(appA, appB))
    }

    @Test
    fun `getCurrentApps fallback survives multiple empty updates`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(emptyList())
        repo.updateApps(emptyList())
        repo.updateApps(emptyList())
        assertThat(repo.getCurrentApps()).isEqualTo(listOf(appA))
    }

    @Test
    fun `getCurrentApps fallback updates on each non-empty update`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(listOf(appB, appC))
        repo.updateApps(emptyList())
        assertThat(repo.getCurrentApps()).isEqualTo(listOf(appB, appC))
    }

    @Test
    fun `getCurrentApps with no prior non-empty state returns empty`() {
        val repo = createRepository()
        repo.updateApps(emptyList())
        assertThat(repo.getCurrentApps()).isEqualTo(emptyList<AppInfo>())
    }
}
