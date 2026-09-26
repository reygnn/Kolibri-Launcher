package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.NoAutoPruneContract
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import kotlinx.coroutines.flow.first

/**
 * nyx side of [NoAutoPruneContract]. Lives in `:nyx:app` (not `:nyx:domain`) on purpose:
 * nyx's curated store is the HOME LAYOUT, and the one place a home tile meets
 * installed-ness is the render mapping ([HomeLayout.pageCells] → [HomeCell.App.missing]).
 * The reconcile ([ReconcileHomeLayoutUseCase], `:domain`) is STRUCTURAL-ONLY and takes NO
 * installed set, so a `:domain`-only realization could not establish the contract's
 * NON-EMPTY-installed-view precondition — its "survives" would be tautological (the
 * reconcile can't prune by installed-ness no matter what).
 *
 * This realizes the contract for real, with two independent teeth:
 * 1. It runs the REAL [ReconcileHomeLayoutUseCase]; its 3-arg constructor call is also a
 *    COMPILE-TIME guard — re-adding an installed-set prune parameter would break this call
 *    and force a review here.
 * 2. It then renders the reconciled layout through [pageCells] against a NON-EMPTY installed
 *    view that OMITS the candidate. The candidate must survive as a greyed "missing" tile,
 *    never be dropped. An auto-prune re-introduced at EITHER the reconcile or the render
 *    site turns this red (the render assertion is the non-tautological half F10 was missing).
 */
class NyxNoAutoPruneTest : NoAutoPruneContract() {

    private val installed = ComponentKey("com.parity.installed", "com.parity.installed.Main")

    override suspend fun candidateSurvivesUninstall(): Boolean {
        // A structurally-clean layout (no dups / folders / trailing empty pages): candidate
        // tile alongside one genuinely-installed tile.
        val layout = HomeLayout(
            GridSpec(columns = 4, rows = 6),
            pages = 1,
            items = listOf(
                PlacedItem(HomeItem.App(ItemId("candidate"), candidate), CellPos(0, 0, 0)),
                PlacedItem(HomeItem.App(ItemId("installed"), installed), CellPos(0, 1, 0)),
            ),
            dock = emptyList(),
        )
        val layoutRepo = FakeHomeLayoutRepository(layout)

        // Tooth #1: the real, structural-only reconcile. The 3-arg call is the compile-time
        // guard against a re-introduced installed-set prune parameter.
        ReconcileHomeLayoutUseCase(
            layoutRepo,
            ItemIdFactory { ItemId("new") },
            mainDispatcherRule.testDispatcher,
        )()

        val reconciled = layoutRepo.layout().first()

        // Tooth #2: the contract precondition, realized. A NON-EMPTY installed view that
        // OMITS the candidate (the other tile's app IS installed) drives the real missing
        // model. The candidate must still be present, flagged missing — kept, not pruned.
        val installedView = setOf(installed)
        val cells = reconciled.pageCells(page = 0, installed = installedView)
        return cells.any { it is HomeCell.App && it.key == candidate && it.missing }
    }
}
