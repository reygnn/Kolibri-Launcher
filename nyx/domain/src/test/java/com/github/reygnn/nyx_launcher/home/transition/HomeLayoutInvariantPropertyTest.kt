package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.FolderEditResult
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LayoutEditResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.github.reygnn.nyx_launcher.home.model.invariantViolations
import com.github.reygnn.nyx_launcher.home.testing.RandomHomeLayouts
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Ignore
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertIs

/**
 * Property test for the whole transition surface: apply long chains of RANDOM
 * (move / place / removeFromFolder / renameFolder) edits to random-but-VALID
 * layouts and assert the structural invariants (IHM-INV-3/-4/-7, RFF-INV-1) hold
 * after every accepted edit, and that [HomeLayoutReconciler] output is valid and
 * idempotent. This closes the combinatorial gap the example-based tests can't:
 * source-type x target-type x occupant x scope x page-fullness x grid-shape x
 * dock-capacity (grid dimensions and the app pool are randomized per run, so the
 * dock capacity — = columns — varies from 2 upward instead of the fixed 4).
 *
 * The randomized walk here is exactly the one that surfaced the FolderDissolved
 * cell-collision (extract a member from a 2-member folder onto an out-of-range /
 * fresh-page GridInsert -> the freed folder cell was reused for the extracted
 * member AND the promoted survivor). [dissolve_extract_onto_out_of_range_grid_insert_does_not_collide]
 * pins that exact case as a fast regression.
 */
class HomeLayoutInvariantPropertyTest {

    private val pool = (1..12).map { ComponentKey.of("com.app$it", "Main") }
    private fun ck(i: Int) = pool[i]

    // ---------------------------------------------------------------- regression
    @Test
    fun dissolve_extract_onto_out_of_range_grid_insert_does_not_collide() {
        val folder = HomeItem.Folder(ItemId("f"), "", listOf(ck(8), ck(10)))
        val start = HomeLayout(
            grid = GridSpec(4, 5),
            pages = 3,
            items = listOf(PlacedItem(folder, CellPos(0, 0, 0))),
            dock = emptyList(),
        )
        var idc = 0
        val newId = { ItemId("n${idc++}") }
        // extract ck(8) onto a fresh trailing page, index past the last cell
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck(8), DropTarget.GridInsert(page = 3, index = 20), newId,
        )
        val out = r.layout
        assertWithMessage("expected a dissolve with a layout, got $r").that(out != null).isTrue()
        out!!
        assertWithMessage("invariants must hold: ${out.invariantViolations()}").that(out.invariantViolations().isEmpty()).isTrue()
        // both apps now exist top-level, each exactly once
        val keys = out.items.mapNotNull { (it.item as? HomeItem.App)?.key }
        assertThat(keys.count { it == ck(8) }).isEqualTo(1)
        assertThat(keys.count { it == ck(10) }).isEqualTo(1)
    }

    /**
     * Dock-folder parity with the grid: dropping a member back onto the SOURCE
     * folder's OWN dock slot is refused (mirrors the grid own-cell rejection),
     * rather than silently dissolving the folder.
     */
    @Test
    fun extract_onto_own_dock_folder_slot_is_rejected() {
        val start = HomeLayout(
            grid = GridSpec(4, 5),
            pages = 1,
            items = emptyList(),
            dock = listOf(HomeItem.Folder(ItemId("f"), "", listOf(ck(1), ck(2)))),
        )
        var idc = 0
        val newId = { ItemId("n${idc++}") }
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck(1), DropTarget.DockItem(0), newId,
        )
        assertIs<FolderEditResult.Rejected>(r, "own-slot drop must be rejected, got $r")
        // the folder is untouched (no dissolve, dock unchanged)
        assertWithMessage("layout must be unchanged on reject").that(r.layout == null).isTrue()
    }

    // ---------------------------------------------------------------- property
    /** CI-tempo run: ~80k transitions, seeded, deterministic. */
    @Test
    fun random_edit_chains_preserve_all_invariants() = walk(runs = 2000, steps = 40)

    /**
     * Heavy soak: ~1.2M transitions. [Ignore]d so `./gradlew test` stays fast —
     * run explicitly for a big reproducible pass, e.g.
     * `./gradlew :nyx:domain:test --tests '*HomeLayoutInvariantPropertyTest.heavy_soak*'`
     * after removing @Ignore, or from the IDE. Same generator/logic as the fast
     * test, just more rounds; the fast test + regression case already gate CI.
     */
    @Ignore("soak: ~1.2M transitions; run on demand / nightly, not per-commit")
    @Test
    fun heavy_soak_random_edit_chains_1_2M() = walk(runs = 20000, steps = 60)

    /** The shared random-walk property: every accepted edit — and every reconcile — stays invariant-valid. */
    private fun walk(runs: Int, steps: Int) {
        for (run in 0 until runs) {
            val rnd = Random(run.toLong() * 1_000_003L + 7)
            val sc = RandomHomeLayouts.scenario(rnd)
            var cur = RandomHomeLayouts.seed(rnd, sc)
            assertWithMessage("seed invalid (run=$run $sc): ${cur.invariantViolations()}").that(cur.invariantViolations().isEmpty()).isTrue()
            var idc = 0
            val newId = { ItemId("g${run}_${idc++}") }
            for (step in 0 until steps) {
                val ids = cur.items.map { it.item.id } + cur.dock.map { it.id }
                if (ids.isEmpty()) continue
                val res: LayoutEditResult? = when (rnd.nextInt(100)) {
                    in 0 until 40 -> HomeLayoutTransition.move(cur, ids[rnd.nextInt(ids.size)], target(cur, rnd), newId)
                    in 40 until 65 -> HomeLayoutTransition.place(cur, sc.pool[rnd.nextInt(sc.pool.size)], target(cur, rnd), newId)
                    in 65 until 90 -> {
                        val fs = (cur.items.map { it.item } + cur.dock).filterIsInstance<HomeItem.Folder>()
                        if (fs.isEmpty()) null else fs[rnd.nextInt(fs.size)].let { f ->
                            HomeLayoutTransition.removeFromFolder(cur, f.id, f.members[rnd.nextInt(f.members.size)], target(cur, rnd), newId)
                        }
                    }
                    else -> {
                        val fs = (cur.items.map { it.item } + cur.dock).filterIsInstance<HomeItem.Folder>()
                        if (fs.isEmpty()) null else HomeLayoutTransition.renameFolder(cur, fs[rnd.nextInt(fs.size)].id, "t$step")
                    }
                }
                val before = cur
                val next = res?.layout ?: continue
                cur = next
                val bad = cur.invariantViolations()
                if (bad.isNotEmpty()) throw AssertionError("run=$run step=$step $sc violations=$bad\nlayout=$cur")
                // §Audit-2 N14: conservation — no app key silently disappears across an accepted
                // edit. place() may ADD a key; move / removeFromFolder / renameFolder conserve
                // exactly, so a superset always holds. A structurally-valid-but-app-losing result
                // (which invariantViolations alone would not catch) fails here.
                if (!cur.appKeys().containsAll(before.appKeys())) {
                    throw AssertionError("run=$run step=$step $sc lost app keys ${before.appKeys() - cur.appKeys()}\nlayout=$cur")
                }

                if (step % 20 == 19) {
                    // Structural reconcile only (no prune): idempotency + invariant-safety.
                    val r1 = HomeLayoutReconciler.reconcile(cur, newId)
                    val after1 = (r1 as? ReconcileOutcome.Changed)?.layout ?: cur
                    val rbad = after1.invariantViolations()
                    if (rbad.isNotEmpty()) throw AssertionError("reconcile invalid run=$run step=$step $sc $rbad")
                    val r2 = HomeLayoutReconciler.reconcile(after1, newId)
                    assertWithMessage("reconcile not idempotent run=$run step=$step $sc").that(r2).isInstanceOf(ReconcileOutcome.Unchanged::class.java)
                    // §Audit-2 N14: reconcile is no-prune → the DISTINCT app-key set is conserved
                    // exactly (dedup only removes duplicates that survive elsewhere in the set).
                    assertWithMessage("reconcile changed the app-key set run=$run step=$step $sc").that(after1.appKeys()).isEqualTo(cur.appKeys())
                    cur = after1
                }
            }
        }
    }

    // ---------------------------------------------------------------- generators
    // Layout generators (Scenario / scenario / seed) live in the shared fixture
    // RandomHomeLayouts so :data can reuse them; `target` is transition-specific.

    /** Every distinct app [ComponentKey] present anywhere in the layout (grid, dock, folders). */
    private fun HomeLayout.appKeys(): Set<ComponentKey> {
        val keys = HashSet<ComponentKey>()
        fun collect(item: HomeItem) {
            when (item) {
                is HomeItem.App -> keys.add(item.key)
                is HomeItem.Folder -> keys.addAll(item.members)
            }
        }
        items.forEach { collect(it.item) }
        dock.forEach { collect(it) }
        return keys
    }

    private fun target(l: HomeLayout, rnd: Random): DropTarget = when (rnd.nextInt(4)) {
        0 -> DropTarget.Cell(CellPos(rnd.nextInt(l.pages + 1), rnd.nextInt(l.grid.columns + 1), rnd.nextInt(l.grid.rows + 1)))
        1 -> DropTarget.GridInsert(rnd.nextInt(l.pages + 1), rnd.nextInt(l.grid.columns * l.grid.rows + 2))
        2 -> DropTarget.DockSlot(rnd.nextInt(l.dock.size + 2))
        else -> DropTarget.DockItem(rnd.nextInt(l.dock.size + 2))
    }
}
