package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.installedapps.FakeAppEnumerator
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.model.SkipReason
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * FAIL-CLOSED reconcile (RHL-INV-1): only a genuine non-empty enumeration reconciles;
 * a thrown enumeration (LOAD_FAILED) or an empty result (LOAD_EMPTY) Skip with zero
 * mutation + zero save. Reads the SHARED [com.github.reygnn.launcher.core.AppEnumerator]
 * directly (F5) — always fresh, no StateFlow replay.
 */
class ReconcileHomeLayoutUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("new") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun appInfo(p: String) = AppInfo(originalName = p, displayName = p, packageName = p, className = "$p.Main")

    private fun layoutWith(vararg pkgs: String): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = pkgs.mapIndexed { i, p -> PlacedItem(HomeItem.App(ItemId(p), ck(p)), CellPos(0, i, 0)) },
        dock = emptyList(),
    )

    private fun useCase(layoutRepo: FakeHomeLayoutRepository, enumerator: FakeAppEnumerator) =
        ReconcileHomeLayoutUseCase(layoutRepo, enumerator, ids, mainDispatcherRule.dispatcher)

    @Test
    fun failed_load_is_skipped_and_never_saves() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(throwable = RuntimeException("enumeration boom"))

        val result = useCase(layoutRepo, enumerator)()

        assertThat(result).isEqualTo(ReconcileResult.Skipped(SkipReason.LOAD_FAILED))
        assertThat(layoutRepo.saveCount).isEqualTo(0) // FAIL-CLOSED: home untouched
        assertThat(layoutRepo.current.items).hasSize(2)
    }

    @Test
    fun empty_load_is_skipped_and_never_saves() = runTest(mainDispatcherRule.dispatcher) {
        // An empty/partial enumeration is suspicious (a real device has >= 1 app) →
        // LOAD_EMPTY skip. Still fail-closed: never empty the home screen.
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = emptyList())

        val result = useCase(layoutRepo, enumerator)()

        assertThat(result).isEqualTo(ReconcileResult.Skipped(SkipReason.LOAD_EMPTY))
        assertThat(layoutRepo.saveCount).isEqualTo(0)
        assertThat(layoutRepo.current.items).hasSize(2)
    }

    @Test
    fun loaded_with_dead_app_prunes_and_saves_once() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"))) // pb uninstalled

        val result = useCase(layoutRepo, enumerator)()

        assertThat(result).isInstanceOf(ReconcileResult.Reconciled::class.java)
        assertThat(layoutRepo.saveCount).isEqualTo(1)
        assertThat(layoutRepo.current.items.map { it.item.id }).containsExactly(ItemId("pa"))
    }

    @Test
    fun loaded_all_installed_is_unchanged_and_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"), appInfo("pb")))

        val result = useCase(layoutRepo, enumerator)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0)
    }

    /**
     * F5 FRESHNESS PIN. Reconcile must prune against the CURRENT enumeration, not a
     * stale snapshot. The enumerator starts reporting both apps installed, then pb is
     * uninstalled (its enumeration result changes) BEFORE reconcile runs; reconcile
     * must read the fresh (pb-gone) list and prune the pb tile. If reconcile ever
     * reverts to reading a cached/stale loader value (the F5 regression), pb would
     * survive and this fails.
     */
    @Test
    fun reconcile_prunes_against_the_fresh_enumeration_not_a_stale_snapshot() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"), appInfo("pb"))) // both present initially
        // Uninstall lands: the live launchable set no longer contains pb.
        enumerator.result = listOf(appInfo("pa"))

        val result = useCase(layoutRepo, enumerator)()

        assertThat(result).isInstanceOf(ReconcileResult.Reconciled::class.java)
        assertThat(layoutRepo.saveCount).isEqualTo(1)
        assertThat(layoutRepo.current.items.map { it.item.id }).containsExactly(ItemId("pa"))
    }
}
