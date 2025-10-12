package com.github.reygnn.kolibri_launcher

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class GetFavoriteAppsUseCaseTest {

    @Mock private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @Mock private lateinit var favoritesManager: FavoritesRepository
    @Mock private lateinit var favoritesOrderManager: FavoritesOrderManager
    @Mock private lateinit var appVisibilityManager: AppVisibilityRepository

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
        MockitoAnnotations.openMocks(this)

        rawAppsFlow = MutableStateFlow(emptyList())
        favoritesFlow = MutableStateFlow(emptySet())
        hiddenAppsFlow = MutableStateFlow(emptySet())
        orderFlow = MutableStateFlow(emptyList())

        whenever(installedAppsStateRepository.rawAppsFlow).thenReturn(rawAppsFlow)
        whenever(favoritesManager.favoriteComponentsFlow).thenReturn(favoritesFlow)
        whenever(appVisibilityManager.hiddenAppsFlow).thenReturn(hiddenAppsFlow)
        whenever(favoritesOrderManager.favoriteComponentsOrderFlow).thenReturn(orderFlow)

        useCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesManager,
            favoritesOrderManager,
            appVisibilityManager
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `favoriteApps returns correctly identified and sorted apps`() = runTest {
        val customSortedFavorites = listOf(app2, app1) // C, dann A
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doReturn(customSortedFavorites)

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertEquals(2, result.apps.size)
            assertEquals("App C", result.apps[0].displayName)
            assertEquals("App A", result.apps[1].displayName)
            assertFalse(result.isFallback)
        }
    }

    @Test
    fun `favoriteApps returns default fallback apps when no favorites are set`() = runTest {
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).thenReturn(emptyList())

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = emptySet()
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            val expectedFallbackSize = AppConstants.MAX_FAVORITES_ON_HOME.coerceAtMost(allApps.size)

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
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doAnswer {
            throw RuntimeException("Sorting failed")
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data

            // Should fallback to alphabetical sorting
            assertEquals(2, result.apps.size)
            assertEquals("App A", result.apps[0].displayName)
            assertEquals("App C", result.apps[1].displayName)
        }
    }

    @Test
    fun `favoriteApps - when sortFavoriteComponents throws IOException - handles gracefully`() = runTest {
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doAnswer {
            throw IOException("Cannot read order")
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app3.componentName)
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertEquals(1, result.apps.size)
            assertEquals("App B", result.apps[0].displayName)
        }
    }

    @Test
    fun `favoriteApps - when favoritesFlow crashes - uses empty set fallback and shows fallback apps`() = runTest {
        whenever(favoritesManager.favoriteComponentsFlow).thenReturn(flow {
            throw IOException("Cannot read favorites")
        })
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).thenReturn(emptyList())

        val crashingUseCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesManager,
            favoritesOrderManager,
            appVisibilityManager
        )

        crashingUseCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps

            // Mit .catch() Handler wird ein Success mit Fallback emittiert, kein Error
            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertTrue(result.isFallback, "Should be fallback since favorites crashed")
            assertTrue(result.apps.isNotEmpty(), "Should have fallback apps")
        }
    }

    @Test
    fun `favoriteApps - when hiddenAppsFlow crashes - treats all apps as visible`() = runTest {
        whenever(appVisibilityManager.hiddenAppsFlow).thenReturn(flow {
            throw RuntimeException("Cannot read hidden apps")
        })
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doAnswer { invocation ->
            val apps = invocation.getArgument<List<AppInfo>>(0)
            apps
        }

        val crashingUseCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesManager,
            favoritesOrderManager,
            appVisibilityManager
        )

        crashingUseCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName)
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertNotNull(result.apps)
            assertEquals(1, result.apps.size) // app1 is the favorite
        }
    }

    @Test
    fun `favoriteApps - with all favorites hidden - returns fallback`() = runTest {
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).thenReturn(emptyList())

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps
            // Erste Emission: Fallback mit allen 3 Apps (keine Favorites gesetzt)
            val firstEmission = awaitItem()
            assertTrue(firstEmission is UiState.Success)

            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            // Zweite Emission: Mit Favorites aber orderedFavorites ist leer (Mock)
            val secondEmission = awaitItem()
            assertTrue(secondEmission is UiState.Success)

            hiddenAppsFlow.value = setOf(app1.componentName, app2.componentName)
            // Dritte Emission: Favoriten sind versteckt, Fallback nur mit app3
            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertTrue(result.isFallback)
            assertEquals(1, result.apps.size)
            assertEquals("App B", result.apps[0].displayName)
        }
    }

    @Test
    fun `favoriteApps - with malformed componentNames in favorites - filters them out`() = runTest {
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doAnswer { invocation ->
            val apps = invocation.getArgument<List<AppInfo>>(0)
            apps
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            // Favorites contains valid and invalid entries
            favoritesFlow.value = setOf(app1.componentName, "", "invalid", app2.componentName)
            rawAppsFlow.value = allApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertEquals(2, result.apps.size)
        }
    }

    @Test
    fun `favoriteApps - with very large favorites list - handles efficiently`() = runTest {
        val largeFavoritesList = (1..100).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }

        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doReturn(largeFavoritesList)

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = largeFavoritesList.map { it.componentName }.toSet()
            rawAppsFlow.value = largeFavoritesList

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertEquals(100, result.apps.size)
        }
    }

    @Test
    fun `favoriteApps - when only some favorites exist in installed apps - returns existing ones`() = runTest {
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doAnswer { invocation ->
            val apps = invocation.getArgument<List<AppInfo>>(0)
            apps
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps
            // Erste Emission: Fallback (keine Favorites)
            awaitItem()

            // Favorites include uninstalled apps
            favoritesFlow.value = setOf(
                app1.componentName,
                "com.uninstalled/App",
                app2.componentName
            )

            // Zweite Emission: Nur app1 und app2 als Favorites
            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertEquals(2, result.apps.size)
            assertFalse(result.isFallback)
        }
    }

    @Test
    fun `favoriteApps - rapid flow updates - handles correctly`() = runTest {
        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).doAnswer { invocation ->
            val apps = invocation.getArgument<List<AppInfo>>(0)
            apps
        }

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            rawAppsFlow.value = allApps
            // Erste Emission: Fallback mit 3 Apps (keine Favorites)
            val initial = awaitItem()
            assertTrue(initial is UiState.Success)
            assertEquals(3, (initial as UiState.Success).data.apps.size)

            // First update
            favoritesFlow.value = setOf(app1.componentName)
            val first = awaitItem()
            assertTrue(first is UiState.Success)
            assertEquals(1, (first as UiState.Success).data.apps.size)

            // Second update
            favoritesFlow.value = setOf(app1.componentName, app2.componentName)
            val second = awaitItem()
            assertTrue(second is UiState.Success)
            assertEquals(2, (second as UiState.Success).data.apps.size)
        }
    }

    @Test
    fun `favoriteApps - with empty raw apps but favorites set - emits loading`() = runTest {
        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = setOf(app1.componentName)
            // rawAppsFlow stays empty

            expectNoEvents()
        }
    }

    @Test
    fun `favoriteApps - when fallback size exceeds MAX_FAVORITES - limits correctly`() = runTest {
        val manyApps = (1..50).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }

        whenever(favoritesOrderManager.sortFavoriteComponents(any(), any())).thenReturn(emptyList())

        useCase.favoriteApps.test {
            assertEquals(UiState.Loading, awaitItem())

            favoritesFlow.value = emptySet()
            rawAppsFlow.value = manyApps

            val successState = awaitItem()
            assertTrue(successState is UiState.Success)

            val result = (successState as UiState.Success).data
            assertTrue(result.isFallback)
            assertEquals(AppConstants.MAX_FAVORITES_ON_HOME, result.apps.size)
        }
    }
}