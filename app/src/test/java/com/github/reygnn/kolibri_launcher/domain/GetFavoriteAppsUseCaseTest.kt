package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.coerceAtMostSafe
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiState
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
    val timberRule = TimberRule()

    @MockK
    private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @MockK
    private lateinit var favoritesRepository: FavoritesRepository
    @MockK
    private lateinit var favoritesOrderRepository: FavoritesOrderRepository
    @MockK
    private lateinit var hiddenAppsRepository: HiddenAppsRepository

    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>
    private lateinit var favoritesFlow: MutableStateFlow<Set<String>>
    private lateinit var hiddenAppsFlow: MutableStateFlow<Set<String>>
    private lateinit var orderFlow: MutableStateFlow<List<String>>

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

        every { installedAppsStateRepository.rawAppsFlow } returns rawAppsFlow
        every { favoritesRepository.favoriteComponentsFlow } returns favoritesFlow
        every { hiddenAppsRepository.hiddenAppsFlow } returns hiddenAppsFlow
        every { favoritesOrderRepository.favoriteComponentsOrderFlow } returns orderFlow

        useCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesRepository,
            favoritesOrderRepository,
            hiddenAppsRepository
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
            assertEquals(UiState.Loading, awaitItem())

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
            val expectedFallbackSize = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME.coerceAtMostSafe(allApps.size)

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
            assertEquals(UiState.Loading, awaitItem())

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
            assertEquals(UiState.Loading, awaitItem())

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
            hiddenAppsRepository
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
            hiddenAppsRepository
        )

        crashingUseCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName)
            rawAppsFlow.value = allApps
            assertEquals(UiState.Loading, awaitItem())

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

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            val secondEmission = awaitItem()
            assertTrue(secondEmission is UiState.Success)

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
            assertEquals(UiState.Loading, awaitItem())

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(2, result.apps.size)
        }
    }

    @Test
    fun `favoriteApps - with very large favorites list - handles efficiently`() = runTest {
        val limit = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
        val exceededCount = limit + 1
        val largeFavoritesList = (1..exceededCount).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }

        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns largeFavoritesList

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = largeFavoritesList.map { it.componentName }.toSet()
            rawAppsFlow.value = largeFavoritesList
            assertEquals(UiState.Loading, awaitItem())

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = successState.data
            assertEquals(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME, result.apps.size)
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
            assertEquals(UiState.Loading, awaitItem())

            expectNoEvents()
        }
    }

    @Test
    fun `favoriteApps - when fallback size exceeds MAX_FAVORITES - limits correctly`() = runTest {
        val limit = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
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
            assertEquals(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME, result.apps.size)
        }
    }
}