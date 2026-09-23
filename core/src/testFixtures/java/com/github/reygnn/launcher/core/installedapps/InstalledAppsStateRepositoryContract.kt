package com.github.reygnn.launcher.core.installedapps

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
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
 * Uses [MainDispatcherRuleBase] with a single `StandardTestDispatcher` (family
 * convention: one dispatcher source, no ad-hoc TestScope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class InstalledAppsStateRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

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
        assertEquals(emptyList<AppInfo>(), createRepository().rawAppsFlow.value)
    }

    @Test
    fun `fresh repository returns empty from getCurrentApps`() {
        assertEquals(emptyList<AppInfo>(), createRepository().getCurrentApps())
    }

    // ---------- updateApps + rawAppsFlow ----------

    @Test
    fun `updateApps reflects in rawAppsFlow value`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        assertEquals(listOf(appA, appB), repo.rawAppsFlow.value)
    }

    @Test
    fun `updateApps reflects in getCurrentApps`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        assertEquals(listOf(appA, appB), repo.getCurrentApps())
    }

    @Test
    fun `updateApps preserves input order`() {
        val repo = createRepository()
        val input = listOf(appC, appA, appB) // deliberately non-alphabetical
        repo.updateApps(input)
        assertEquals(input, repo.getCurrentApps())
    }

    @Test
    fun `updateApps overwrites previous state`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(listOf(appB, appC))
        assertEquals(listOf(appB, appC), repo.getCurrentApps())
    }

    // ---------- Last-known-good (SIA-INV-5) — the important property ----------

    @Test
    fun `getCurrentApps falls back to last non-empty list when state is empty`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        repo.updateApps(emptyList())

        // Direct flow read sees the explicit "empty" state.
        assertEquals(emptyList<AppInfo>(), repo.rawAppsFlow.value)
        // getCurrentApps falls back to last known good.
        assertEquals(listOf(appA, appB), repo.getCurrentApps())
    }

    @Test
    fun `getCurrentApps fallback survives multiple empty updates`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(emptyList())
        repo.updateApps(emptyList())
        repo.updateApps(emptyList())
        assertEquals(listOf(appA), repo.getCurrentApps())
    }

    @Test
    fun `getCurrentApps fallback updates on each non-empty update`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(listOf(appB, appC))
        repo.updateApps(emptyList())
        assertEquals(listOf(appB, appC), repo.getCurrentApps())
    }

    @Test
    fun `getCurrentApps with no prior non-empty state returns empty`() {
        val repo = createRepository()
        repo.updateApps(emptyList())
        assertEquals(emptyList<AppInfo>(), repo.getCurrentApps())
    }
}
