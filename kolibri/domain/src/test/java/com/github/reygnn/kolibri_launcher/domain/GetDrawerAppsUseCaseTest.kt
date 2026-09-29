package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.core.testing.recordEmissions
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

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
    private lateinit var usageSnapshotFlow: MutableStateFlow<Map<String, List<Long>>>

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
        usageSnapshotFlow = MutableStateFlow(emptyMap())

        every { installedAppsStateRepository.rawAppsFlow } returns rawAppsFlow
        every { hiddenAppsRepository.hiddenAppsFlow } returns hiddenAppsFlow
        every { settingsRepository.sortOrderFlow } returns sortOrderFlow
        every { customNamesRepository.customNamesFlow } returns customNamesFlow
        every { appUsageRepository.usageSnapshotFlow } returns usageSnapshotFlow

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
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app2.componentName)
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val drawerApps = results.last()
            assertThat(drawerApps.size).isEqualTo(2)
            assertThat(drawerApps.any { it.componentName == app2.componentName }).isFalse()
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps are sorted alphabetically when sortOrder is Alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.ALPHABETICAL
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val drawerApps = results.last()
            assertThat(drawerApps.size).isEqualTo(3)
            assertThat(drawerApps[0].displayName).isEqualTo("App A")
            assertThat(drawerApps[1].displayName).isEqualTo("App B")
            assertThat(drawerApps[2].displayName).isEqualTo("App C")
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps are sorted by time-weighted usage when sortOrder is correct`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        val timeWeightedSortedList = listOf(app2, app3, app1)
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } returns timeWeightedSortedList

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val drawerApps = results.last()
            assertThat(drawerApps.size).isEqualTo(3)
            assertThat(drawerApps[0].displayName).isEqualTo("App C")
            assertThat(drawerApps[1].displayName).isEqualTo("App B")
            assertThat(drawerApps[2].displayName).isEqualTo("App A")

            coVerify(atLeast = 1) { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps recalculates when sortOrder changes`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.ALPHABETICAL
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val initialDrawerApps = results.last()
            assertThat(initialDrawerApps[0].displayName).isEqualTo("App A")

            val timeWeightedSortedList = listOf(app2, app3, app1)
            coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } returns timeWeightedSortedList

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            advanceUntilIdle()

            val updatedDrawerApps = results.last()
            assertThat(updatedDrawerApps[0].displayName).isEqualTo("App C")
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps is empty when raw app list is empty`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            val drawerApps = results.last()
            assertThat(drawerApps.isEmpty()).isTrue()
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `drawerApps - when appUsageRepository throws exception - falls back to alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } answers {
            throw RuntimeException("Sorting failed")
        }

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val result = results.last()
            assertThat(result.size).isEqualTo(3)
            assertThat(result[0].displayName).isEqualTo("App A")
            assertThat(result[1].displayName).isEqualTo("App B")
            assertThat(result[2].displayName).isEqualTo("App C")
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - when appUsageRepository throws IOException - falls back to alphabetical`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } answers {
            throw IOException("Cannot read usage data")
        }

        try {
            advanceUntilIdle()

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val result = results.last()
            assertThat(result[0].displayName).isEqualTo("App A")
            assertThat(result[1].displayName).isEqualTo("App B")
            assertThat(result[2].displayName).isEqualTo("App C")
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with all apps hidden - returns empty list`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app1.componentName, app2.componentName, app3.componentName)
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertThat(results.last().isEmpty()).isTrue()
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with duplicate apps in raw list - handles gracefully`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            rawAppsFlow.value = listOf(app1, app1, app2, app3)
            advanceUntilIdle()

            val result = results.last()
            assertThat(result).isNotNull()
            assertThat(result.size <= 4).isTrue()
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with very large app list - handles efficiently`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            val largeAppList = (1..1000).map { AppInfo("App $it", "App $it", "com.app$it", "class$it") }
            rawAppsFlow.value = largeAppList
            advanceUntilIdle()

            assertThat(results.last().size).isEqualTo(1000)
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - when filtering creates empty list - returns empty`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = allApps.map { it.componentName }.toSet()
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertThat(results.last().isEmpty()).isTrue()
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - rapid flow updates - handles correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            rawAppsFlow.value = listOf(app1)
            advanceUntilIdle()
            assertThat(results.last().size).isEqualTo(1)

            rawAppsFlow.value = listOf(app1, app2)
            advanceUntilIdle()
            assertThat(results.last().size).isEqualTo(2)

            rawAppsFlow.value = allApps
            advanceUntilIdle()
            assertThat(results.last().size).isEqualTo(3)
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - with null componentNames in hidden set - filters correctly`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()

            hiddenAppsFlow.value = setOf(app1.componentName, "", "invalid/format")
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            val result = results.last()
            assertThat(result.size).isEqualTo(2)
            assertThat(result.any { it.componentName == app1.componentName }).isFalse()
        } finally {
            collectorJob.cancel()
        }
    }

    // ========== AUDIT-14 F2 bullet 1: usageSnapshotFlow only in TIME_WEIGHTED mode ==========

    @Test
    fun `drawerApps - in ALPHABETICAL mode - does not collect usageSnapshotFlow`() = runTest {
        // Default sortOrder is ALPHABETICAL. usageSnapshotFlow must not be an input here,
        // so a per-launch usage tick cannot re-run the pipeline (F2 bullet 1).
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            assertThat(results.last().size).isEqualTo(3)
            verify(exactly = 0) { appUsageRepository.usageSnapshotFlow }
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - in TIME_WEIGHTED mode - collects usageSnapshotFlow`() = runTest {
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } returns allApps
        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps
            advanceUntilIdle()

            // In TIME_WEIGHTED mode usageSnapshotFlow IS an input, so the order re-derives
            // reactively on a tick.
            verify(atLeast = 1) { appUsageRepository.usageSnapshotFlow }
        } finally {
            collectorJob.cancel()
        }
    }

    // ========== AUDIT-14 F2: behavioral pinning of the flatMapLatest refactor ==========
    // The two tests above only prove SUBSCRIPTION (getter accessed / not accessed).
    // These pin the actual reactive behavior the refactor promises. A real usage
    // tick needs a SharedFlow: a conflating MutableStateFlow cannot re-emit an
    // equal snapshot, whereas production usageSnapshotFlow re-emits a fresh parsed
    // snapshot per real usage change (the value is ignored here — the mock sort
    // returns a captured order).

    @Test
    fun `drawerApps - in TIME_WEIGHTED mode - a usage tick re-derives the order`() = runTest {
        val usageTicks = MutableSharedFlow<Map<String, List<Long>>>(replay = 1, extraBufferCapacity = 8)
        usageTicks.emit(emptyMap()) // initial value so the inner combine can proceed
        every { appUsageRepository.usageSnapshotFlow } returns usageTicks

        // The mock ignores its input; a captured var flips the returned order so the
        // second (tick-driven) derivation differs from the first.
        var weightedOrder = listOf(app2, app3, app1) // App C, App B, App A
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } answers { weightedOrder }

        sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
        rawAppsFlow.value = allApps

        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()
            assertThat(results.last()[0].displayName).isEqualTo("App C")

            // A usage tick must re-run the pipeline and surface the new order.
            weightedOrder = listOf(app1, app3, app2) // App A, App B, App C
            usageTicks.emit(emptyMap())
            advanceUntilIdle()

            assertThat(results.last()[0].displayName).isEqualTo("App A")
            coVerify(atLeast = 2) { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - in ALPHABETICAL mode - a real usage tick causes no re-emission`() = runTest {
        val usageTicks = MutableSharedFlow<Map<String, List<Long>>>(replay = 1, extraBufferCapacity = 8)
        usageTicks.emit(emptyMap())
        every { appUsageRepository.usageSnapshotFlow } returns usageTicks

        rawAppsFlow.value = allApps // sortOrder stays ALPHABETICAL (default)

        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()
            val emissionsBefore = results.size
            assertThat(results.last().size).isEqualTo(3)

            // Even a DELIVERABLE tick (SharedFlow emits every value) must not reach
            // the ALPHABETICAL pipeline — usageSnapshotFlow is not one of its inputs (F2 #1).
            repeat(3) { usageTicks.emit(emptyMap()) }
            advanceUntilIdle()

            assertThat(results.size).isEqualTo(emissionsBefore)
            coVerify(exactly = 0) { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `drawerApps - switching TIME_WEIGHTED to ALPHABETICAL tears down the usage subscription`() =
        runTest {
            val usageTicks = MutableSharedFlow<Map<String, List<Long>>>(replay = 1, extraBufferCapacity = 8)
            usageTicks.emit(emptyMap())
            every { appUsageRepository.usageSnapshotFlow } returns usageTicks

            val weightedCalls = AtomicInteger(0)
            coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } answers {
                weightedCalls.incrementAndGet()
                listOf(app2, app3, app1)
            }

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            rawAppsFlow.value = allApps

            val results = mutableListOf<List<AppInfo>>()
            val collectorJob = recordEmissions(useCase.drawerApps, into = results)

            try {
                advanceUntilIdle()
                assertThat(results.last()[0].displayName).isEqualTo("App C")

                // flatMapLatest cancels the 4-way inner combine (incl. usage) and
                // builds the 3-way one.
                sortOrderFlow.value = SortOrder.ALPHABETICAL
                advanceUntilIdle()
                assertThat(results.last()[0].displayName).isEqualTo("App A")

                val callsAfterSwitch = weightedCalls.get()
                val emissionsAfterSwitch = results.size

                // Ticks now hit a torn-down subscription: no recompute, no emission.
                repeat(3) { usageTicks.emit(emptyMap()) }
                advanceUntilIdle()

                assertThat(weightedCalls.get()).isEqualTo(callsAfterSwitch)
                assertThat(results.size).isEqualTo(emissionsAfterSwitch)
            } finally {
                collectorJob.cancel()
            }
        }

    @Test
    fun `drawerApps - mode switch that yields identical ordering emits nothing`() = runTest {
        // TIME_WEIGHTED returns exactly the alphabetical order of allApps
        // (App A, App B, App C = app1, app3, app2), so the switch changes nothing.
        coEvery { appUsageRepository.sortAppsByTimeWeightedUsage(any(), any()) } returns
            listOf(app1, app3, app2)

        rawAppsFlow.value = allApps // ALPHABETICAL default

        val results = mutableListOf<List<AppInfo>>()
        val collectorJob = recordEmissions(useCase.drawerApps, into = results)

        try {
            advanceUntilIdle()
            val emissionsBefore = results.size
            assertThat(results.last().map { it.displayName }).isEqualTo(listOf("App A", "App B", "App C"))

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE
            advanceUntilIdle()

            // The terminal distinctUntilChanged sits downstream of flatMapLatest, so
            // its last value persists across the inner-flow rebuild: an identical
            // ordering is suppressed rather than churning the adapter.
            assertThat(results.size).isEqualTo(emissionsBefore)
        } finally {
            collectorJob.cancel()
        }
    }
}