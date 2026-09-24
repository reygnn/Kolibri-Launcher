package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppPresence
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.DeletionGateParityContract
import com.github.reygnn.launcher.core.InstallSessionInspector
import com.github.reygnn.launcher.core.installedapps.FakeAppEnumerator
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository

/**
 * nyx side of the cross-launcher [DeletionGateParityContract] (root TODO.md step b). The candidate
 * is placed as a home tile alongside one genuinely-installed tile; [ReconcileHomeLayoutUseCase] runs
 * against an enumeration that omits the candidate; survival is read back from the persisted layout.
 */
class NyxDeletionGateParityTest : DeletionGateParityContract() {

    private val installedApp = ComponentKey("com.parity.installed", "com.parity.installed.Main")

    private class FakePresence(private val present: Set<ComponentKey>) : AppPresence {
        override suspend fun isComponentPresent(key: ComponentKey) = key in present
        override suspend fun isPackagePresent(packageName: String) = present.any { it.packageName == packageName }
    }

    private class FakeSessions(private val active: Set<String>?) : InstallSessionInspector {
        override suspend fun activeSessionPackages(): Set<String>? = active
    }

    override suspend fun componentCandidateSurvives(
        presentComponents: Set<ComponentKey>,
        activeSessions: Set<String>?,
    ): Boolean {
        val layout = HomeLayout(
            GridSpec(columns = 4, rows = 6),
            pages = 1,
            items = listOf(
                PlacedItem(HomeItem.App(ItemId("candidate"), candidate), CellPos(0, 0, 0)),
                PlacedItem(HomeItem.App(ItemId("installed"), installedApp), CellPos(0, 1, 0)),
            ),
            dock = emptyList(),
        )
        val layoutRepo = FakeHomeLayoutRepository(layout)
        // Non-empty enumeration that OMITS the candidate → the candidate is a prune candidate,
        // the installed tile is not (it stays in `installed` and is never gated).
        val enumerator = FakeAppEnumerator(
            result = listOf(
                AppInfo(installedApp.packageName, installedApp.packageName, installedApp.packageName, installedApp.className),
            ),
        )
        ReconcileHomeLayoutUseCase(
            layoutRepo,
            enumerator,
            FakePresence(presentComponents),
            FakeSessions(activeSessions),
            ItemIdFactory { ItemId("new") },
            mainDispatcherRule.testDispatcher,
        )()
        return layoutRepo.snapshot().items.any { (it.item as? HomeItem.App)?.key == candidate }
    }
}
