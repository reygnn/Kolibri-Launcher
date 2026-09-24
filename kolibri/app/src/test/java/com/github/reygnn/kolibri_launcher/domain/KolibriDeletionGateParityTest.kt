package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.DeletionGateParityContract
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeAppPresence
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstallSessionInspector
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import org.junit.Rule

/**
 * kolibri side of the cross-launcher [DeletionGateParityContract] (root TODO.md step b). The
 * candidate is placed in the hidden-apps store (component-keyed); [ObserveInstalledAppsUseCase] runs
 * the load reconcile against an enumeration that omits it; survival is read back from that store.
 * The other three stores stay empty, so only the hidden store's gate wiring is exercised.
 */
class KolibriDeletionGateParityTest : DeletionGateParityContract() {

    @get:Rule
    val timberRule = TimberRule()

    private val installedApp = AppInfo("inst", "inst", "com.parity.installed", "com.parity.installed.Main")

    override suspend fun componentCandidateSurvives(
        presentComponents: Set<ComponentKey>,
        activeSessions: Set<String>?,
    ): Boolean {
        val hidden = FakeHiddenAppsRepository().apply { hiddenApps = setOf(candidate.flat) }
        val presence = FakeAppPresence()
        presence.presentComponents = presentComponents
        presence.presentPackages = presentComponents.mapTo(HashSet()) { it.packageName }
        val sessions = FakeInstallSessionInspector()
        if (activeSessions == null) sessions.undetermined = true else sessions.active = activeSessions

        // Non-empty load that OMITS the candidate → the candidate is a prune candidate.
        val repository = FakeInstalledAppsRepository().apply { installedApps = listOf(installedApp) }

        val useCase = ObserveInstalledAppsUseCase(
            repository,
            FakeInstalledAppsStateRepository(),
            FakeFavoritesRepository(),
            FakeSwipeActionsRepository(),
            hidden,
            FakeCustomNamesRepository(),
            presence,
            sessions,
        )
        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return candidate.flat in hidden.hiddenApps
    }
}
