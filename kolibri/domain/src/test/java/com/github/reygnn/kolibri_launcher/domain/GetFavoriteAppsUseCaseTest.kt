package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.coerceAtMostSafe
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.service.ComponentLabelResolver
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class GetFavoriteAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @MockK
    private lateinit var favoritesRepository: FavoritesRepository
    @MockK
    private lateinit var favoritesOrderRepository: FavoritesOrderRepository
    @MockK
    private lateinit var hiddenAppsRepository: HiddenAppsRepository
    @MockK
    private lateinit var customNamesRepository: CustomNamesRepository
    @MockK
    private lateinit var componentLabelResolver: ComponentLabelResolver

    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>
    private lateinit var favoritesFlow: MutableStateFlow<Set<String>>
    private lateinit var hiddenAppsFlow: MutableStateFlow<Set<String>>
    private lateinit var orderFlow: MutableStateFlow<List<String>>
    private lateinit var customNamesFlow: MutableStateFlow<Map<String, String>>

    private lateinit var useCase: GetFavoriteAppsUseCase

    private val app1 = AppInfo(originalName = "App A", displayName = "App A", packageName = "com.a", className = "MainActivity")
    private val app2 = AppInfo(originalName = "App C", displayName = "App C", packageName = "com.c", className = "MainActivity")
    private val app3 = AppInfo(originalName = "App B", displayName = "App B", packageName = "com.b", className = "MainActivity")
    private val allApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        rawAppsFlow = MutableStateFlow(emptyList())
        favoritesFlow = MutableStateFlow(emptySet())
        hiddenAppsFlow = MutableStateFlow(emptySet())
        orderFlow = MutableStateFlow(emptyList())
        customNamesFlow = MutableStateFlow(emptyMap())

        every { installedAppsStateRepository.rawAppsFlow } returns rawAppsFlow
        every { favoritesRepository.favoriteComponentsFlow } returns favoritesFlow
        every { hiddenAppsRepository.hiddenAppsFlow } returns hiddenAppsFlow
        every { favoritesOrderRepository.favoriteComponentsOrderFlow } returns orderFlow
        every { customNamesRepository.customNamesFlow } returns customNamesFlow
        // Live first-paint resolver: default returns null (component not resolvable),
        // so every EXISTING test keeps its `Loading` first emission (no favorite
        // resolves → no provisional). The provisional-specific tests below stub real
        // labels per component.
        coEvery { componentLabelResolver.resolveLabel(any()) } returns null

        useCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesRepository,
            favoritesOrderRepository,
            hiddenAppsRepository,
            customNamesRepository,
            componentLabelResolver,
            dispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `favoriteApps returns correctly identified and sorted apps`() = runTest {
        val customSortedFavorites = listOf(app2, app1)
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns customSortedFavorites

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            // No second Loading here any more: the favorites-set re-emission over
            // the still-empty raw list is identical to the first Loading and is
            // now collapsed by distinctUntilChanged (AUDIT-14 F1).

            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(2, result.apps.size)
            assertEquals("App C", result.apps[0].displayName)
            assertEquals("App A", result.apps[1].displayName)
            assertFalse(result.isFallback)
        }
    }

    @Test
    fun `favoriteApps returns default fallback apps when no favorites are set`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns emptyList()

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = emptySet()
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            val expectedFallbackSize = AppConstants.MAX_FAVORITES_ON_HOME.coerceAtMostSafe(allApps.size)

            assertEquals(expectedFallbackSize, result.apps.size)
            assertEquals("App A", result.apps[0].displayName)
            assertEquals("App B", result.apps[1].displayName)
            assertEquals("App C", result.apps[2].displayName)
            assertTrue(result.isFallback, "Should be a fallback")
        }
    }

    @Test
    fun `favoriteApps emits Loading and does not proceed when raw app list is empty`() = runTest {
        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())
            expectNoEvents()
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `favoriteApps - when sortFavoriteComponents throws exception - falls back to alphabetical`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            throw RuntimeException("Sorting failed")
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            rawAppsFlow.value = allApps
            // distinctUntilChanged collapses the redundant Loading re-emission (AUDIT-14 F1)

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(2, result.apps.size)
            assertEquals("App A", result.apps[0].displayName)
            assertEquals("App C", result.apps[1].displayName)
        }
    }

    @Test
    fun `favoriteApps - when sortFavoriteComponents throws IOException - handles gracefully`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            throw IOException("Cannot read order")
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app3.componentName)
            rawAppsFlow.value = allApps
            // distinctUntilChanged collapses the redundant Loading re-emission (AUDIT-14 F1)

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(1, result.apps.size)
            assertEquals("App B", result.apps[0].displayName)
        }
    }

    @Test
    fun `favoriteApps - when favoritesFlow crashes - uses empty set fallback and shows fallback apps`() = runTest {
        every { favoritesRepository.favoriteComponentsFlow } returns flow {
            throw IOException("Cannot read favorites")
        }
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns emptyList()

        val crashingUseCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesRepository,
            favoritesOrderRepository,
            hiddenAppsRepository,
            customNamesRepository,
            componentLabelResolver,
            dispatcher = mainDispatcherRule.testDispatcher
        )

        crashingUseCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertTrue(result.isFallback, "Should be fallback since favorites crashed")
            assertTrue(result.apps.isNotEmpty(), "Should have fallback apps")
        }
    }

    @Test
    fun `favoriteApps - when hiddenAppsFlow crashes - treats all apps as visible`() = runTest {
        every { hiddenAppsRepository.hiddenAppsFlow } returns flow {
            throw RuntimeException("Cannot read hidden apps")
        }
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        val crashingUseCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesRepository,
            favoritesOrderRepository,
            hiddenAppsRepository,
            customNamesRepository,
            componentLabelResolver,
            dispatcher = mainDispatcherRule.testDispatcher
        )

        crashingUseCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName)
            rawAppsFlow.value = allApps
            // distinctUntilChanged collapses the redundant Loading re-emission (AUDIT-14 F1)

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertNotNull(result.apps)
            assertEquals(1, result.apps.size)
        }
    }

    @Test
    fun `favoriteApps - with all favorites hidden - returns fallback`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns emptyList()

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps
            val firstEmission = awaitItem()
            assertTrue(firstEmission is UiState.Success)

            // Marking these two favorites produces the SAME fallback output as
            // before (sortFavoriteComponents is mocked to empty → still fallback,
            // hidden set unchanged), so distinctUntilChanged emits nothing here
            // (AUDIT-14 F1). The observable change comes from the hide below.
            favoritesFlow.value = setOf(app1.componentName, app2.componentName)

            hiddenAppsFlow.value = setOf(app1.componentName, app2.componentName)
            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertTrue(result.isFallback)
            assertEquals(1, result.apps.size)
            assertEquals("App B", result.apps[0].displayName)
        }
    }

    @Test
    fun `favoriteApps - with malformed componentNames in favorites - filters them out`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName, "", "invalid", app2.componentName)
            rawAppsFlow.value = allApps
            // distinctUntilChanged collapses the redundant Loading re-emission (AUDIT-14 F1)

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(2, result.apps.size)
        }
    }

    @Test
    fun `favoriteApps - with very large favorites list - handles efficiently`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val exceededCount = limit + 1
        val largeFavoritesList = (1..exceededCount).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }

        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns largeFavoritesList

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = largeFavoritesList.map { it.componentName }.toSet()
            rawAppsFlow.value = largeFavoritesList
            // distinctUntilChanged collapses the redundant Loading re-emission (AUDIT-14 F1)

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(AppConstants.MAX_FAVORITES_ON_HOME, result.apps.size)
        }
    }

    @Test
    fun `favoriteApps - when only some favorites exist in installed apps - returns existing ones`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps
            awaitItem()

            favoritesFlow.value = setOf(app1.componentName, "com.uninstalled/App", app2.componentName)

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(2, result.apps.size)
            assertFalse(result.isFallback)
        }
    }

    @Test
    fun `favoriteApps - rapid flow updates - handles correctly`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps
            val initial = awaitItem()
            assertTrue(initial is UiState.Success)
            assertEquals(3, initial.data.apps.size)

            favoritesFlow.value = setOf(app1.componentName)
            val first = awaitItem()
            assertTrue(first is UiState.Success)
            assertEquals(1, first.data.apps.size)

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            val second = awaitItem()
            assertTrue(second is UiState.Success)
            assertEquals(2, second.data.apps.size)
        }
    }

    @Test
    fun `favoriteApps - with empty raw apps but favorites set - emits loading`() = runTest {
        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName)
            // Raw list still empty → the re-emission is another identical Loading,
            // now collapsed by distinctUntilChanged, so nothing new arrives.
            expectNoEvents()
        }
    }

    @Test
    fun `favoriteApps - when fallback size exceeds MAX_FAVORITES - limits correctly`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val exceededCount = limit + 1
        val manyApps = (1..exceededCount).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }

        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns emptyList()

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = emptySet()
            rawAppsFlow.value = manyApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertTrue(result.isFallback)
            assertEquals(AppConstants.MAX_FAVORITES_ON_HOME, result.apps.size)
        }
    }

    // ========== FIRST-PAINT PROVISIONAL FAVORITES (live label resolution) ==========

    @Test
    fun `favoriteApps emits provisional favorites from live labels while raw apps still empty`() = runTest {
        // Cold-start shape: enumeration has not run (rawApps empty), but the favorite
        // set + order + custom names are already read from DataStore. The favorite
        // labels are resolved LIVE (targeted per-component lookup) so the favorites
        // paint at once instead of Loading. app1 is a RENAMED favorite (custom
        // displayName "Config", true live label "Settings") to pin that the
        // provisional AppInfo carries the TRUE originalName, not the custom-name
        // placeholder (reset-rename edge).
        favoritesFlow.value = setOf(app1.componentName, app2.componentName)
        customNamesFlow.value = mapOf(app1.packageName to "Config")
        coEvery { componentLabelResolver.resolveLabel(app1.componentName) } returns "Settings"
        coEvery { componentLabelResolver.resolveLabel(app2.componentName) } returns "App C"
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            val provisional = awaitItem()
            assertTrue(provisional is UiState.Success)
            assertFalse(provisional.data.isFallback)
            assertEquals(listOf("Config", "App C"), provisional.data.apps.map { it.displayName })
            // originalName is the TRUE live label, so a reset-rename clears the
            // override instead of persisting a spurious custom name.
            assertEquals("Settings", provisional.data.apps[0].originalName)
            // componentName round-trips so DiffUtil identity matches the authoritative
            // entry that later replaces this provisional one in place.
            assertEquals(app1.componentName, provisional.data.apps[0].componentName)
            assertTrue(provisional.data.apps[0].isFavorite)
            expectNoEvents()
        }
    }

    @Test
    fun `favoriteApps replaces provisional with authoritative result once enumerated`() = runTest {
        favoritesFlow.value = setOf(app1.componentName)
        // Live label at provisional time differs from the enumeration's label (e.g.
        // app updated between) so the swap is observable; when they match, the
        // redundant re-emission is collapsed by distinctUntilChanged.
        coEvery { componentLabelResolver.resolveLabel(app1.componentName) } returns "Old A"
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            val provisional = awaitItem()
            assertTrue(provisional is UiState.Success)
            assertEquals(listOf("Old A"), provisional.data.apps.map { it.displayName })

            rawAppsFlow.value = allApps

            val authoritative = awaitItem()
            assertTrue(authoritative is UiState.Success)
            // Real label from the enumeration replaces the provisional one in place.
            assertEquals(listOf("App A"), authoritative.data.apps.map { it.displayName })
            assertFalse(authoritative.data.isFallback)
        }
    }

    @Test
    fun `favoriteApps omits a favorite whose component no longer resolves - no ghost`() = runTest {
        // A favorite uninstalled while the launcher was dead: its live lookup returns
        // null, so it is simply omitted from the provisional paint (no ghost), unlike
        // a persisted cache which would show it until reconciliation.
        favoritesFlow.value = setOf(app1.componentName, "com.dead/Gone")
        coEvery { componentLabelResolver.resolveLabel(app1.componentName) } returns "App A"
        coEvery { componentLabelResolver.resolveLabel("com.dead/Gone") } returns null
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            val provisional = awaitItem()
            assertTrue(provisional is UiState.Success)
            assertEquals(listOf("App A"), provisional.data.apps.map { it.displayName })
            assertFalse(provisional.data.isFallback)
            expectNoEvents()
        }
    }

    @Test
    fun `favoriteApps provisional replays the order returned by sortFavoriteComponents`() = runTest {
        // The provisional list must respect whatever sortFavoriteComponents returns
        // (the saved-order arrangement), not the raw favorite-set iteration order.
        favoritesFlow.value = setOf(app1.componentName, app2.componentName)
        coEvery { componentLabelResolver.resolveLabel(app1.componentName) } returns "App A"
        coEvery { componentLabelResolver.resolveLabel(app2.componentName) } returns "App C"
        // Reverse-alphabetical on purpose: set order is [App A, App C], so this proves
        // the provisional replays the sort result rather than the set order.
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>().sortedByDescending { it.displayName }
        }

        useCase.favoriteApps.test {
            val provisional = awaitItem()
            assertTrue(provisional is UiState.Success)
            assertEquals(listOf("App C", "App A"), provisional.data.apps.map { it.displayName })
        }
    }

    @Test
    fun `favoriteApps provisional truncates to MAX_FAVORITES_ON_HOME`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val many = (1..(limit + 3)).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }
        favoritesFlow.value = many.map { it.componentName }.toSet()
        many.forEach { app ->
            coEvery { componentLabelResolver.resolveLabel(app.componentName) } returns app.displayName
        }
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            val provisional = awaitItem()
            assertTrue(provisional is UiState.Success)
            assertEquals(limit, provisional.data.apps.size)
        }
    }

    // ========== ARCHITECTURE RULE: favorite status breaks the hidden filter ==========
    //
    // The class KDoc's central rule — an installed favorite that is ALSO hidden stays
    // pinned on the home screen — had no direct test. The existing
    // `with all favorites hidden - returns fallback` mocks sortFavoriteComponents to
    // emptyList(), so its `isFallback` comes from the forced-empty sort, not from the
    // hidden logic. These pin the rule with an identity sort, so a hidden filter
    // wrongly added to the favorites path (the exact thing the KDoc forbids) turns
    // them red.

    @Test
    fun `favoriteApps - an installed favorite that is also hidden stays pinned on home`() = runTest {
        // Identity sort — NOT mocked empty — so the favorites survive unless a hidden
        // filter wrongly drops them.
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            // Both favorites are ALSO hidden — they must still appear.
            hiddenAppsFlow.value = setOf(app1.componentName, app2.componentName)
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)
            val result = successState.data

            assertFalse(result.isFallback, "A hidden favorite must stay pinned, not fall back")
            assertEquals(2, result.apps.size)
            assertEquals(
                setOf(app1.componentName, app2.componentName),
                result.apps.map { it.componentName }.toSet(),
            )
            assertTrue(result.apps.all { it.isFavorite })
        }
    }

    @Test
    fun `favoriteApps - a mix of hidden and visible favorites all stay on home`() = runTest {
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            // Only app1 is hidden; app2 is visible. Neither may be dropped.
            hiddenAppsFlow.value = setOf(app1.componentName)
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)
            val result = successState.data

            assertFalse(result.isFallback)
            assertEquals(
                setOf(app1.componentName, app2.componentName),
                result.apps.map { it.componentName }.toSet(),
            )
        }
    }

    @Test
    fun `favoriteApps - favorites set but all uninstalled - falls back via the filter, not a mocked-empty sort`() = runTest {
        // Distinct from `returns default fallback apps when no favorites are set` and
        // from the all-hidden test: here favorites ARE set and the sort is identity —
        // the favorites list empties purely because NONE of the components are
        // installed (the processApps filter-empty branch), which no test pinned.
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            firstArg<List<AppInfo>>()
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf("com.gone.one/Main", "com.gone.two/Main")
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)
            val result = successState.data

            assertTrue(result.isFallback, "All favorites uninstalled must fall back to top-N")
            // Fallback = all visible apps, alphabetical.
            assertEquals(listOf("App A", "App B", "App C"), result.apps.map { it.displayName })
        }
    }
}