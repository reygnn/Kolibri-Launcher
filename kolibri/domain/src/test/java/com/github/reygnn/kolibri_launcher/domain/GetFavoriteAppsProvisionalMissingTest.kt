package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.domain.service.ComponentLabelResolver
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the provisional first-paint behaviour AFTER the ghost-free rule was dropped: while
 * the raw app list is still empty (cold start), a favorite whose app is gone is painted
 * IMMEDIATELY as a greyed "missing" entry, not omitted — so it does not pop in ~150 ms later
 * (parity with nyx tiles). Reverting [GetFavoriteAppsUseCase.buildProvisional] to drop
 * unresolvable favorites turns [a provisional missing favorite is kept] red.
 *
 * Deliberately separate from GetFavoriteAppsUseCaseTest: that suite's default
 * `resolveLabel → null` stub encodes the OLD "null ⇒ no provisional ⇒ Loading" contract,
 * which this change inverts; those tests migrate under the new contract.
 */
@ExperimentalCoroutinesApi
class GetFavoriteAppsProvisionalMissingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val present = ComponentKey("com.present", "com.present.Main")
    private val ghost = ComponentKey("com.ghost.gone", "com.ghost.gone.Main")

    private fun useCase(resolver: ComponentLabelResolver): GetFavoriteAppsUseCase {
        // Raw list stays EMPTY → the flow takes the provisional (RawStep.Empty) path.
        val state = FakeInstalledAppsStateRepository()
        val favorites = FakeFavoritesRepository().apply {
            favorites = setOf(present.flat, ghost.flat)
        }
        return GetFavoriteAppsUseCase(
            state,
            favorites,
            FakeFavoritesOrderRepository(),
            FakeHiddenAppsRepository(),
            FakeCustomNamesRepository(),
            resolver,
            dispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    @Test
    fun `a provisional missing favorite is kept, greyed, alongside a resolvable one`() = runTest {
        val resolver = mockk<ComponentLabelResolver>()
        coEvery { resolver.resolveLabel(present.flat) } returns "Present" // installed → real label
        coEvery { resolver.resolveLabel(ghost.flat) } returns null        // gone → no live label

        useCase(resolver).favoriteApps.test {
            val state = awaitItem()
            assertTrue("expected a provisional Success, not Loading", state is UiState.Success)
            val result = state.data

            // The gone favorite SURVIVES the provisional paint (not dropped) …
            assertTrue(result.apps.any { it.componentName == ghost.flat })
            // … and is flagged missing (so the row renders greyed / tap offers removal) …
            assertTrue(ghost.flat in result.missingComponents)
            // … while the installed favorite is present and NOT flagged.
            assertTrue(result.apps.any { it.componentName == present.flat })
            assertFalse(present.flat in result.missingComponents)

            assertEquals(2, result.apps.size)
            assertFalse(result.isFallback)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
