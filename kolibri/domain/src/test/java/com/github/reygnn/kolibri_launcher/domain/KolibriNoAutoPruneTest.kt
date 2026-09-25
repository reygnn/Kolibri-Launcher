package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.NoAutoPruneContract
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.domain.service.ComponentLabelResolver
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.first

/**
 * kolibri side of [NoAutoPruneContract]. The candidate is a favorite alongside one
 * genuinely-installed favorite; the app-state holder carries a NON-EMPTY loaded list that
 * OMITS the candidate (the post-load state after that app was uninstalled). Survival is read
 * back from what [GetFavoriteAppsUseCase] actually renders — the candidate must still appear
 * (as a synthesized "missing" entry), never be dropped.
 *
 * The loader itself ([ObserveInstalledAppsUseCase]) no longer touches the curated stores at
 * all since the no-prune rebuild, so the meaningful, teeth-bearing guard lives one layer out
 * at the consumer: if favorites-dropping is ever reintroduced anywhere in this chain, the
 * candidate disappears from the emission and this turns red.
 */
class KolibriNoAutoPruneTest : NoAutoPruneContract() {

    private val installed = AppInfo(
        originalName = "Installed",
        displayName = "Installed",
        packageName = "com.parity.installed",
        className = "com.parity.installed.Main",
    )

    override suspend fun candidateSurvivesUninstall(): Boolean {
        // Post-load state: a non-empty list that OMITS the candidate (its app is gone) but
        // keeps the other favorite installed.
        val stateRepo = FakeInstalledAppsStateRepository().apply { updateApps(listOf(installed)) }
        val favorites = FakeFavoritesRepository().apply {
            favorites = setOf(candidate.flat, installed.componentName)
        }

        val useCase = GetFavoriteAppsUseCase(
            stateRepo,
            favorites,
            FakeFavoritesOrderRepository(),
            FakeHiddenAppsRepository(),
            FakeCustomNamesRepository(),
            mockk<ComponentLabelResolver>(relaxed = true), // only used on the cold-start provisional path
            mainDispatcherRule.testDispatcher,
        )

        val state = useCase.favoriteApps.first()
        val apps = (state as UiState.Success).data.apps
        return apps.any { it.componentName == candidate.flat }
    }
}
