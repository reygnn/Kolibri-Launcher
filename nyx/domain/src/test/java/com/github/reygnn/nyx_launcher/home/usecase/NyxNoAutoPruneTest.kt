package com.github.reygnn.nyx_launcher.home.usecase

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
import kotlinx.coroutines.flow.first

/**
 * nyx side of [NoAutoPruneContract]. The candidate is a home tile alongside one
 * genuinely-installed tile; the REAL [ReconcileHomeLayoutUseCase] runs; survival is read
 * back from the persisted layout.
 *
 * Since the no-prune rebuild the reconciler is structural-only and takes NO installed set,
 * so it cannot prune by installed-ness — this pins that it stays that way. Two ways a future
 * regression turns this red: the reconciler starts enumerating + pruning internally (the
 * candidate tile is dropped → assertion fails), or an installed-set prune param is re-added
 * to the constructor (this seam's 3-arg call stops compiling → forces a review here).
 */
class NyxNoAutoPruneTest : NoAutoPruneContract() {

    private val installed = ComponentKey("com.parity.installed", "com.parity.installed.Main")

    override suspend fun candidateSurvivesUninstall(): Boolean {
        // A structurally-clean layout (no dups / folders / trailing empty pages) whose ONLY
        // notable property is that one tile (candidate) references an app that is "gone".
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

        ReconcileHomeLayoutUseCase(
            layoutRepo,
            ItemIdFactory { ItemId("new") },
            mainDispatcherRule.testDispatcher,
        )()

        return layoutRepo.layout().first().items.any { (it.item as? HomeItem.App)?.key == candidate }
    }
}
