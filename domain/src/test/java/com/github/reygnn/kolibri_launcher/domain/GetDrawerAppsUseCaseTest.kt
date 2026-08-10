package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
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
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class GetDrawerAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
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
    @MockK
    private lateinit var customNamesRepository: CustomNamesRepository

    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>
    private lateinit var hiddenAppsFlow: MutableStateFlow<Set<String>>
    private lateinit var sortOrderFlow: MutableStateFlow<SortOrder>
    private lateinit var customNamesFlow: MutableStateFlow<Map<String, String>>
    private lateinit var usageFlow: MutableStateFlow<Unit>

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
        customNamesFlow = MutableStateFlow(emptyMap())
        usageFlow = MutableStateFlow(Unit)

        every { installedAppsStateRepository.rawAppsFlow } returns rawAppsFlow
        every { hiddenAppsRepository.hiddenAppsFlow } returns hiddenAppsFlow
        every { settingsRepository.sortOrderFlow } returns sortOrderFlow
        every { customNamesRepository.customNamesFlow } returns customNamesFlow
        every { appUsageRepository.usageFlow } returns usageFlow

        useCase = GetDrawerAppsUseCase(
            appUsageRepository,
            installedAppsStateRepository,
            hiddenAppsRepository,
            settingsRepository,
            customNamesRepository,
            dispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `drawerApps filters hidden apps correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app2.componentName)
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val drawerApps = results.last()
            assertEquals(2, drawerApps.size)
            assertFalse(drawerApps.any { it.componentName == app2.componentName })
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps are sorted alphabetically when sortOrder is Alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

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
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps are sorted by time-weighted usage when sortOrder is correct`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        val timeWeightedSortedList = listOf(app2, app3, app1)
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } returns timeWeightedSortedList

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
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps recalculates when sortOrder changes`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

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
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps is empty when raw app list is empty`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()

            val drawerApps = results.last()
            assertTrue(drawerApps.isEmpty())
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `drawerApps - when appUsageRepository throws exception - falls back to alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } answers {
            throw RuntimeException("Sorting failed")
        }

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
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - when appUsageRepository throws IOException - falls back to alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } answers {
            throw IOException("Cannot read usage data")
        }

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
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with all apps hidden - returns empty list`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app1.componentName, app2.componentName, app3.componentName)
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertTrue(results.last().isEmpty())
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with duplicate apps in raw list - handles gracefully`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()

            rawAppsFlow.value = listOf(app1, app1, app2, app3)
            advanceUntilIdle()

            val result = results.last()
            assertNotNull(result)
            assertTrue(result.size <= 4)
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with very large app list - handles efficiently`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()

            val largeAppList = (1..1000).map { AppInfo("App $it", "App $it", "com.app$it", "class$it") }
            rawAppsFlow.value = largeAppList
            advanceUntilIdle()

            assertEquals(1000, results.last().size)
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - when filtering creates empty list - returns empty`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = allApps.map { it.componentName }.toSet()
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertTrue(results.last().isEmpty())
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - rapid flow updates - handles correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

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
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with null componentNames in hidden set - filters correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app1.componentName, "", "invalid/format")
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val result = results.last()
            assertEquals(2, result.size)
            assertFalse(result.any { it.componentName == app1.componentName })
        } finally {
            collectorJob.cancel()
        }
    }

    // ========== AUDIT-14 F2 bullet 1: usageFlow only in TIME_WEIGHTED mode ==========

    @Test
    fun `drawerApps - in ALPHABETICAL mode - does not collect usageFlow`() = runTest {
        // Default sortOrder is ALPHABETICAL. usageFlow must not be an input here,
        // so a per-launch usage tick cannot re-run the pipeline (F2 bullet 1).
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertEquals(3, results.last().size)
            verify(exactly = 0) { appUsageRepository.usageFlow }
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - in TIME_WEIGHTED mode - collects usageFlow`() = runTest {
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } returns allApps
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            // In TIME_WEIGHTED mode usageFlow IS an input, so the order re-derives
            // reactively on a tick.
            verify(atLeast = 1) { appUsageRepository.usageFlow }
        } finally {
            collectorJob.cancel()
        }
    }

    // ========== AUDIT-14 F2: behavioral pinning of the flatMapLatest refactor ==========
    // The two tests above only prove SUBSCRIPTION (getter accessed / not accessed).
    // These pin the actual reactive behavior the refactor promises. A real usage
    // tick needs a SharedFlow: a MutableStateFlow<Unit> conflates repeated Unit and
    // cannot re-emit, whereas production usageFlow is a Flow<Unit> that ticks per
    // real usage change.

    @Test
    fun `drawerApps - in TIME_WEIGHTED mode - a usage tick re-derives the order`() = runTest {
        val usageTicks = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)
        usageTicks.emit(Unit) // initial value so the inner combine can proceed
        every { appUsageRepository.usageFlow } returns usageTicks

        // The mock ignores its input; a captured var flips the returned order so the
        // second (tick-driven) derivation differs from the first.
        var weightedOrder = listOf(app2, app3, app1) // App C, App B, App A
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } answers { weightedOrder }

        sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
        rawAppsFlow.value = allApps

        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()
            assertEquals("App C", results.last()[0].displayName)

            // A usage tick must re-run the pipeline and surface the new order.
            weightedOrder = listOf(app1, app3, app2) // App A, App B, App C
            usageTicks.emit(Unit)
            advanceUntilIdle()

            assertEquals("App A", results.last()[0].displayName)
            coVerify(atLeast = 2) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - in ALPHABETICAL mode - a real usage tick causes no re-emission`() = runTest {
        val usageTicks = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)
        usageTicks.emit(Unit)
        every { appUsageRepository.usageFlow } returns usageTicks

        rawAppsFlow.value = allApps // sortOrder stays ALPHABETICAL (default)

        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()
            val emissionsBefore = results.size
            assertEquals(3, results.last().size)

            // Even a DELIVERABLE tick (SharedFlow emits every value) must not reach
            // the ALPHABETICAL pipeline — usageFlow is not one of its inputs (F2 #1).
            repeat(3) { usageTicks.emit(Unit) }
            advanceUntilIdle()

            assertEquals(emissionsBefore, results.size)
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - switching TIME_WEIGHTED to ALPHABETICAL tears down the usage subscription`() =
        runTest {
            val usageTicks = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)
            usageTicks.emit(Unit)
            every { appUsageRepository.usageFlow } returns usageTicks

            val weightedCalls = AtomicInteger(0)
            coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } answers {
                weightedCalls.incrementAndGet()
                listOf(app2, app3, app1)
            }

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps

            val results = mutableListOf<List<AppInfo>>()
            val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                useCase.drawerApps.collect { results.add(it) }
            }

            try {
                advanceUntilIdle()
                assertEquals("App C", results.last()[0].displayName)

                // flatMapLatest cancels the 4-way inner combine (incl. usage) and
                // builds the 3-way one.
                sortOrderFlow.value = SortOrder.ALPHABETICAL
                advanceUntilIdle()
                assertEquals("App A", results.last()[0].displayName)

                val callsAfterSwitch = weightedCalls.get()
                val emissionsAfterSwitch = results.size

                // Ticks now hit a torn-down subscription: no recompute, no emission.
                repeat(3) { usageTicks.emit(Unit) }
                advanceUntilIdle()

                assertEquals(callsAfterSwitch, weightedCalls.get())
                assertEquals(emissionsAfterSwitch, results.size)
            } finally {
                collectorJob.cancel()
            }
        }

    @Test
    fun `drawerApps - mode switch that yields identical ordering emits nothing`() = runTest {
        // TIME_WEIGHTED returns exactly the alphabetical order of allApps
        // (App A, App B, App C = app1, app3, app2), so the switch changes nothing.
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any()) } returns
            listOf(app1, app3, app2)

        rawAppsFlow.value = allApps // ALPHABETICAL default

        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }

        try {
            advanceUntilIdle()
            val emissionsBefore = results.size
            assertEquals(listOf("App A", "App B", "App C"), results.last().map { it.displayName })

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            advanceUntilIdle()

            // The terminal distinctUntilChanged sits downstream of flatMapLatest, so
            // its last value persists across the inner-flow rebuild: an identical
            // ordering is suppressed rather than churning the adapter.
            assertEquals(emissionsBefore, results.size)
        } finally {
            collectorJob.cancel()
        }
    }
}