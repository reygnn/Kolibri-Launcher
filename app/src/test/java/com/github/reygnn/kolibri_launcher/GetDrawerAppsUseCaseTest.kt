package com.github.reygnn.kolibri_launcher

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.asFlow
import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class GetDrawerAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @Mock private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @Mock private lateinit var appUsageManager: AppUsageRepository
    @Mock private lateinit var appVisibilityManager: AppVisibilityRepository
    @Mock private lateinit var settingsManager: SettingsRepository

    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>
    private lateinit var hiddenAppsFlow: MutableStateFlow<Set<String>>
    private lateinit var sortOrderFlow: MutableStateFlow<SortOrder>

    private lateinit var useCase: GetDrawerAppsUseCase

    private val app1 = AppInfo(originalName = "App A", displayName = "App A", packageName = "com.a", className = "MainActivity")
    private val app2 = AppInfo(originalName = "App C", displayName = "App C", packageName = "com.c", className = "MainActivity")
    private val app3 = AppInfo(originalName = "App B", displayName = "App B", packageName = "com.b", className = "MainActivity")
    private val allApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        rawAppsFlow = MutableStateFlow(emptyList())
        hiddenAppsFlow = MutableStateFlow(emptySet())
        sortOrderFlow = MutableStateFlow(SortOrder.ALPHABETICAL)

        whenever(installedAppsStateRepository.rawAppsFlow).thenReturn(rawAppsFlow)
        whenever(appVisibilityManager.hiddenAppsFlow).thenReturn(hiddenAppsFlow)
        whenever(settingsManager.sortOrderFlow).thenReturn(sortOrderFlow)

        useCase = GetDrawerAppsUseCase(
            appUsageManager,
            installedAppsStateRepository,
            appVisibilityManager,
            settingsManager,
            dispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `drawerApps filters hidden apps correctly`() = runTest {
        useCase.drawerApps.asFlow().test {
            assertEquals(emptyList(), awaitItem())

            hiddenAppsFlow.value = setOf(app2.componentName)
            rawAppsFlow.value = allApps

            val drawerApps = awaitItem()

            assertEquals(2, drawerApps.size)
            assertFalse(drawerApps.any { it.componentName == app2.componentName })
        }
    }

    @Test
    fun `drawerApps are sorted alphabetically when sortOrder is Alphabetical`() = runTest {
        useCase.drawerApps.asFlow().test {
            assertEquals(emptyList(), awaitItem())

            sortOrderFlow.value = SortOrder.ALPHABETICAL
            rawAppsFlow.value = allApps

            val drawerApps = awaitItem()

            assertEquals(3, drawerApps.size)
            assertEquals("App A", drawerApps[0].displayName)
            assertEquals("App B", drawerApps[1].displayName)
            assertEquals("App C", drawerApps[2].displayName)
        }
    }

    @Test
    fun `drawerApps are sorted by time-weighted usage when sortOrder is correct`() = runTest {
        useCase.drawerApps.asFlow().test {
            assertEquals(emptyList(), awaitItem())

            val timeWeightedSortedList = listOf(app2, app3, app1) // C, B, A
            whenever(appUsageManager.sortAppsByTimeWeightedUsage(any())).thenReturn(timeWeightedSortedList)

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps

            val drawerApps = awaitItem()

            verify(appUsageManager).sortAppsByTimeWeightedUsage(any())
            assertEquals(3, drawerApps.size)
            assertEquals("App C", drawerApps[0].displayName)
            assertEquals("App B", drawerApps[1].displayName)
            assertEquals("App A", drawerApps[2].displayName)
        }
    }

    @Test
    fun `drawerApps recalculates when sortOrder changes`() = runTest {
        useCase.drawerApps.asFlow().test {
            assertEquals(emptyList(), awaitItem())

            sortOrderFlow.value = SortOrder.ALPHABETICAL
            rawAppsFlow.value = allApps
            val initialDrawerApps = awaitItem()
            assertEquals("App A", initialDrawerApps[0].displayName)

            val timeWeightedSortedList = listOf(app2, app3, app1)
            whenever(appUsageManager.sortAppsByTimeWeightedUsage(any())).thenReturn(timeWeightedSortedList)

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE

            val updatedDrawerApps = awaitItem()
            assertEquals("App C", updatedDrawerApps[0].displayName)
        }
    }

    @Test
    fun `drawerApps is empty when raw app list is empty`() = runTest {
        useCase.drawerApps.asFlow().test {
            val drawerApps = awaitItem()

            assertTrue(drawerApps.isEmpty())
            verify(appUsageManager, never()).sortAppsByTimeWeightedUsage(any())
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `drawerApps - when appUsageManager throws exception - falls back to alphabetical`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem() // initial empty

            whenever(appUsageManager.sortAppsByTimeWeightedUsage(any())).doAnswer {
                throw RuntimeException("Sorting failed")
            }

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps

            val result = awaitItem()

            // Assert - sollte alphabetisch sortiert sein als Fallback
            assertEquals(3, result.size)
            assertEquals("App A", result[0].displayName)
            assertEquals("App B", result[1].displayName)
            assertEquals("App C", result[2].displayName)
        }
    }

    @Test
    fun `drawerApps - when appUsageManager throws IOException - falls back to alphabetical`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem()

            whenever(appUsageManager.sortAppsByTimeWeightedUsage(any())).doAnswer {
                throw IOException("Cannot read usage data")
            }

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps

            val result = awaitItem()

            assertEquals("App A", result[0].displayName)
            assertEquals("App B", result[1].displayName)
            assertEquals("App C", result[2].displayName)
        }
    }

    @Test
    fun `drawerApps - with all apps hidden - returns empty list`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem()

            hiddenAppsFlow.value = setOf(app1.componentName, app2.componentName, app3.componentName)
            rawAppsFlow.value = allApps

            val result = awaitItem()

            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `drawerApps - with duplicate apps in raw list - handles gracefully`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem()

            val duplicateApps = listOf(app1, app1, app2, app3)
            rawAppsFlow.value = duplicateApps

            val result = awaitItem()

            // Should handle duplicates without crashing
            assertNotNull(result)
            assertTrue(result.size <= 4)
        }
    }

    @Test
    fun `drawerApps - with very large app list - handles efficiently`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem()

            val largeAppList = (1..1000).map {
                AppInfo("App $it", "App $it", "com.app$it", "class$it")
            }
            rawAppsFlow.value = largeAppList

            val result = awaitItem()

            assertEquals(1000, result.size)
        }
    }

    @Test
    fun `drawerApps - when filtering creates empty list - returns empty`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem()

            // All apps are hidden
            hiddenAppsFlow.value = allApps.map { it.componentName }.toSet()
            rawAppsFlow.value = allApps

            val result = awaitItem()

            assertTrue(result.isEmpty())
            verify(appUsageManager, never()).sortAppsByTimeWeightedUsage(any())
        }
    }

    @Test
    fun `drawerApps - rapid flow updates - handles correctly`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem() // initial

            // Rapid updates
            rawAppsFlow.value = listOf(app1)
            val first = awaitItem()
            assertEquals(1, first.size)

            rawAppsFlow.value = listOf(app1, app2)
            val second = awaitItem()
            assertEquals(2, second.size)

            rawAppsFlow.value = allApps
            val third = awaitItem()
            assertEquals(3, third.size)
        }
    }

    @Test
    fun `drawerApps - with null componentNames in hidden set - filters correctly`() = runTest {
        useCase.drawerApps.asFlow().test {
            awaitItem()

            // Hidden set might contain malformed entries
            hiddenAppsFlow.value = setOf(app1.componentName, "", "invalid/format")
            rawAppsFlow.value = allApps

            val result = awaitItem()

            // Should filter out app1 but keep app2 and app3
            assertEquals(2, result.size)
            assertFalse(result.any { it.componentName == app1.componentName })
        }
    }
}

@ExperimentalCoroutinesApi
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}