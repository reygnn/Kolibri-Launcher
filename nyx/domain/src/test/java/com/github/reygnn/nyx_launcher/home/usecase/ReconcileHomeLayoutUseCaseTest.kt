package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppPresence
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.InstallSessionInspector
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
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * FAIL-CLOSED reconcile (RHL-INV-1): only a genuine non-empty enumeration reconciles;
 * a thrown enumeration (LOAD_FAILED) or an empty result (LOAD_EMPTY) Skip with zero
 * mutation + zero save. Reads the SHARED [com.github.reygnn.launcher.core.AppEnumerator]
 * directly (F5) — always fresh, no StateFlow replay.
 *
 * PARTIAL-SNAPSHOT GATE (RHL-INV-6, AUDIT-1 F7): a non-empty-but-INCOMPLETE enumeration
 * must NOT prune apps that are still installed. A key the layout references but the
 * snapshot missed is re-confirmed through [AppPresence]; a still-present one is protected,
 * a genuinely-absent one is pruned. A complete snapshot consults presence zero times.
 */
class ReconcileHomeLayoutUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("new") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun appInfo(p: String) = AppInfo(originalName = p, displayName = p, packageName = p, className = "$p.Main")

    /**
     * Presence fake. [present] is the set of keys the platform would still resolve; any key
     * not in it is confirmed-absent. Default empty → a candidate is treated as uninstalled,
     * so the pre-F7 prune tests keep their old outcome unchanged. [checked] records every
     * lookup so a test can assert the complete-snapshot path does zero presence IPC.
     */
    private class FakeAppPresence(var present: Set<ComponentKey> = emptySet()) : AppPresence {
        val checked = mutableListOf<ComponentKey>()
        override suspend fun isPresent(key: ComponentKey): Boolean {
            checked += key
            return key in present
        }
    }

    /**
     * Session fake. [active] is the set of package names with an install/restore session in
     * flight. Default empty → no package is being restored, so the pre-fix-3 prune tests keep
     * their outcome. [checked] records lookups so a test can assert the presence short-circuit
     * (a present key must not reach the session probe).
     */
    private class FakeInstallSessions(var active: Set<String> = emptySet()) : InstallSessionInspector {
        val checked = mutableListOf<String>()
        override suspend fun hasActiveSession(packageName: String): Boolean {
            checked += packageName
            return packageName in active
        }
    }

    private fun layoutWith(vararg pkgs: String): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = pkgs.mapIndexed { i, p -> PlacedItem(HomeItem.App(ItemId(p), ck(p)), CellPos(0, i, 0)) },
        dock = emptyList(),
    )

    /** A single grid folder whose members are [pkgs] (folders need >= 2 members). */
    private fun layoutWithFolder(vararg pkgs: String): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = listOf(
            PlacedItem(HomeItem.Folder(ItemId("folder"), title = "", members = pkgs.map { ck(it) }), CellPos(0, 0, 0)),
        ),
        dock = emptyList(),
    )

    private fun useCase(
        layoutRepo: FakeHomeLayoutRepository,
        enumerator: FakeAppEnumerator,
        appPresence: AppPresence = FakeAppPresence(),
        installSessions: InstallSessionInspector = FakeInstallSessions(),
    ) = ReconcileHomeLayoutUseCase(
        layoutRepo, enumerator, appPresence, installSessions, ids, mainDispatcherRule.dispatcher,
    )

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
        // A fully-EMPTY enumeration is suspicious (a real device has >= 1 app) →
        // LOAD_EMPTY skip. (A non-empty PARTIAL load is handled by the presence gate,
        // not here — see the partial_snapshot_* tests.) Still fail-closed: never empty
        // the home screen.
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

    // ---- PARTIAL-SNAPSHOT GATE (RHL-INV-6, AUDIT-1 F7) ----

    /**
     * FAIL-CLOSED candidate read. If the layout store read for candidate computation
     * throws (transient DataStore error), the reconcile aborts and writes NOTHING — it
     * does not degrade to an empty layout and prune. Contrast with the fail-OPEN [layout]
     * flow, under which the same error would yield an empty layout → no candidates →
     * `pb` pruned against the partial snapshot. That regression is exactly what this pins.
     */
    @Test
    fun snapshot_read_failure_aborts_reconcile_without_pruning() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb")).apply {
            failSnapshotWith = IOException("transient store read")
        }
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"))) // pb missing this pass

        assertFailsWith<IOException> { useCase(layoutRepo, enumerator)() }
        assertThat(layoutRepo.saveCount).isEqualTo(0) // nothing pruned on a bad read
    }

    /**
     * THE F7 FIX. A non-empty but PARTIAL snapshot (pb missing) must not prune pb while
     * pb is in fact still installed — the presence gate re-confirms pb and protects it.
     * Mutation check: delete the gate (prune straight against `installed`) and pb is
     * pruned → Reconciled + saveCount 1, so this test goes red. That is its whole point.
     */
    @Test
    fun partial_snapshot_does_not_prune_a_still_present_app() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"))) // pb missing THIS pass
        val presence = FakeAppPresence(present = setOf(ck("pb"))) // …but pb is really still there

        val result = useCase(layoutRepo, enumerator, presence)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0) // pb protected: home untouched
        assertThat(layoutRepo.current.items.map { it.item.id })
            .containsExactly(ItemId("pa"), ItemId("pb"))
        assertThat(presence.checked).containsExactly(ck("pb")) // only the missing key is gated
    }

    /**
     * The gate must not over-protect: a candidate that is absent AND has no install session
     * is still pruned. Distinguishes "transiently absent / being restored" (keep) from
     * "actually uninstalled" (prune), and pins that the session probe IS consulted once
     * presence reports absent (the OR reaches its second arm).
     */
    @Test
    fun partial_snapshot_still_prunes_a_genuinely_absent_app() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa")))
        val presence = FakeAppPresence(present = emptySet()) // pb confirmed gone
        val sessions = FakeInstallSessions(active = emptySet()) // …and not being restored

        val result = useCase(layoutRepo, enumerator, presence, sessions)()

        assertThat(result).isInstanceOf(ReconcileResult.Reconciled::class.java)
        assertThat(layoutRepo.saveCount).isEqualTo(1)
        assertThat(layoutRepo.current.items.map { it.item.id }).containsExactly(ItemId("pa"))
        assertThat(sessions.checked).containsExactly("pb") // absent → session arm consulted
    }

    /**
     * SESSION GATE (fix 3, Launcher3-style promise). A candidate the presence check reports
     * ABSENT is still kept when its package has an install/restore session in flight — the
     * mid-restore vector no presence check can close. Mutation check: drop the `|| session`
     * arm and pb is pruned → red.
     */
    @Test
    fun partial_snapshot_protects_an_app_with_an_active_install_session() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"))) // pb missing this pass
        val presence = FakeAppPresence(present = emptySet()) // pb not installed *yet*
        val sessions = FakeInstallSessions(active = setOf("pb")) // …but a restore is in flight

        val result = useCase(layoutRepo, enumerator, presence, sessions)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0) // pb kept as a "promise"
        assertThat(layoutRepo.current.items.map { it.item.id })
            .containsExactly(ItemId("pa"), ItemId("pb"))
    }

    /**
     * SHORT-CIRCUIT. A present candidate is kept by the first arm alone; the session probe
     * must not be consulted (presence is the cheaper, primary signal).
     */
    @Test
    fun present_candidate_short_circuits_the_session_probe() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"))) // pb missing this pass
        val presence = FakeAppPresence(present = setOf(ck("pb"))) // pb present via presence
        val sessions = FakeInstallSessions()

        val result = useCase(layoutRepo, enumerator, presence, sessions)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(sessions.checked).isEmpty() // never reached the second arm
    }

    /**
     * COMMON-PATH COST PIN. A complete snapshot (every layout key present) yields zero
     * prune candidates, so neither gate is consulted — no per-reconcile IPC is added to
     * the healthy path.
     */
    @Test
    fun complete_snapshot_consults_neither_gate() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"), appInfo("pb")))
        val presence = FakeAppPresence()
        val sessions = FakeInstallSessions()

        val result = useCase(layoutRepo, enumerator, presence, sessions)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(presence.checked).isEmpty()
        assertThat(sessions.checked).isEmpty()
    }

    /**
     * The gate covers FOLDER MEMBERS too (referencedKeys spans both prune scopes,
     * RHL-INV-4): a folder member missing from a partial snapshot but still installed is
     * protected, so the folder keeps both members and does not dissolve.
     */
    @Test
    fun partial_snapshot_protects_a_still_present_folder_member() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWithFolder("pa", "pb"))
        val enumerator = FakeAppEnumerator(result = listOf(appInfo("pa"))) // pb missing this pass
        val presence = FakeAppPresence(present = setOf(ck("pb"))) // pb still installed

        val result = useCase(layoutRepo, enumerator, presence)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0)
        val folder = layoutRepo.current.items.single().item as HomeItem.Folder
        assertThat(folder.members).containsExactly(ck("pa"), ck("pb"))
        assertThat(presence.checked).containsExactly(ck("pb"))
    }
}
