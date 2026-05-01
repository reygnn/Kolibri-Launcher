package com.github.reygnn.kolibri_launcher.domain

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
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
class GetDrawerAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()
    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @MockK
    private lateinit var appUsageRepository: AppUsageRepository
    @MockK
    private lateinit var hiddenAppsRepository: HiddenAppsRepository
    @MockK
    private lateinit var settingsRepository: SettingsRepository

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
        MockKAnnotations.init(this)

        rawAppsFlow = MutableStateFlow(emptyList())
        hiddenAppsFlow = MutableStateFlow(emptySet())
        sortOrderFlow = MutableStateFlow(SortOrder.ALPHABETICAL)

        every { installedAppsStateRepository.rawAppsFlow } returns rawAppsFlow
        every { hiddenAppsRepository.hiddenAppsFlow } returns hiddenAppsFlow
        every { settingsRepository.sortOrderFlow } returns sortOrderFlow

        useCase = GetDrawerAppsUseCase(
            appUsageRepository,
            installedAppsStateRepository,
            hiddenAppsRepository,
            settingsRepository,
            dispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `drawerApps filters hidden apps correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app2.componentName)
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val drawerApps = results.last()
            assertEquals(2, drawerApps.size)
            assertFalse(drawerApps.any { it.componentName == app2.componentName })
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps are sorted alphabetically when sortOrder is Alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.ALPHABETICAL
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val drawerApps = results.last()
            assertEquals(3, drawerApps.size)
            assertEquals("App A", drawerApps[0].displayName)
            assertEquals("App B", drawerApps[1].displayName)
            assertEquals("App C", drawerApps[2].displayName)
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps are sorted by time-weighted usage when sortOrder is correct`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        val timeWeightedSortedList = listOf(app2, app3, app1)
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } returns timeWeightedSortedList

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val drawerApps = results.last()
            assertEquals(3, drawerApps.size)
            assertEquals("App C", drawerApps[0].displayName)
            assertEquals("App B", drawerApps[1].displayName)
            assertEquals("App A", drawerApps[2].displayName)

            coVerify(atLeast = 1) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps recalculates when sortOrder changes`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.ALPHABETICAL
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val initialDrawerApps = results.last()
            assertEquals("App A", initialDrawerApps[0].displayName)

            val timeWeightedSortedList = listOf(app2, app3, app1)
            coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } returns timeWeightedSortedList

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            advanceUntilIdle()

            val updatedDrawerApps = results.last()
            assertEquals("App C", updatedDrawerApps[0].displayName)
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps is empty when raw app list is empty`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            val drawerApps = results.last()
            assertTrue(drawerApps.isEmpty())
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `drawerApps - when appUsageRepository throws exception - falls back to alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } answers {
            throw RuntimeException("Sorting failed")
        }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val result = results.last()
            assertEquals(3, result.size)
            assertEquals("App A", result[0].displayName)
            assertEquals("App B", result[1].displayName)
            assertEquals("App C", result[2].displayName)
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps - when appUsageRepository throws IOException - falls back to alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } answers {
            throw IOException("Cannot read usage data")
        }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val result = results.last()
            assertEquals("App A", result[0].displayName)
            assertEquals("App B", result[1].displayName)
            assertEquals("App C", result[2].displayName)
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps - with all apps hidden - returns empty list`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app1.componentName, app2.componentName, app3.componentName)
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertTrue(results.last().isEmpty())
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps - with duplicate apps in raw list - handles gracefully`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            rawAppsFlow.value = listOf(app1, app1, app2, app3)
            advanceUntilIdle()

            val result = results.last()
            assertNotNull(result)
            assertTrue(result.size <= 4)
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps - with very large app list - handles efficiently`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            val largeAppList = (1..1000).map { AppInfo("App $it", "App $it", "com.app$it", "class$it") }
            rawAppsFlow.value = largeAppList
            advanceUntilIdle()

            assertEquals(1000, results.last().size)
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps - when filtering creates empty list - returns empty`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = allApps.map { it.componentName }.toSet()
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertTrue(results.last().isEmpty())
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps - rapid flow updates - handles correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            rawAppsFlow.value = listOf(app1)
            advanceUntilIdle()
            assertEquals(1, results.last().size)

            rawAppsFlow.value = listOf(app1, app2)
            advanceUntilIdle()
            assertEquals(2, results.last().size)

            rawAppsFlow.value = allApps
            advanceUntilIdle()
            assertEquals(3, results.last().size)
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }

    @Test
    fun `drawerApps - with null componentNames in hidden set - filters correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val observer = Observer<List<AppInfo>> { results.add(it) }

        useCase.drawerApps.observeForever(observer)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app1.componentName, "", "invalid/format")
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val result = results.last()
            assertEquals(2, result.size)
            assertFalse(result.any { it.componentName == app1.componentName })
        } finally {
            useCase.drawerApps.removeObserver(observer)
        }
    }
}