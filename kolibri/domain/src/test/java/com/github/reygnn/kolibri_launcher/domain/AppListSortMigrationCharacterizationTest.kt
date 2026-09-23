package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * GOLDEN-MASTER characterization for the shared-installed-apps migration
 * (docs/EXECUTION_SHARED_INSTALLED_APPS.md, delta §1/§2).
 *
 * WHY THIS EXISTS. Today the enumerator returns a list ALREADY sorted
 * (`processResolveInfoList` ends with `sortedByDisplayName()`), so the in-RAM
 * holder happens to hold sorted data. After the migration the shared holder holds
 * the list **raw / unsorted** (SIA-INV-3) and sorting is the consumer's job. These
 * tests pin that `GetDrawerAppsUseCase` produces a fully-sorted, order-invariant
 * result **regardless of the raw input order** — i.e. that the consumer-side sort
 * (and its terminal `distinctUntilChanged`) is what actually guarantees the drawer
 * order, not the enumerator. Run them GREEN against `main` first, then GREEN again
 * after the holder is switched to raw: identical observable behaviour = no
 * regression from the sort move. If a later change removes the consumer sort on the
 * assumption "the holder is already sorted", these fail.
 *
 * MIGRATION NOTE. On migration `AppInfo` moves to
 * `com.github.reygnn.launcher.core` and loses `isFavorite`; update the imports here
 * (the constructor calls below already pass no `isFavorite`). The assertions —
 * observable output order and emission count — are unchanged.
 *
 * Convention: single dispatcher via [MainDispatcherRule]; no separate TestScope /
 * StandardTestDispatcher (see TESTING_CONVENTIONS.kt).
 */
@ExperimentalCoroutinesApi
class AppListSortMigrationCharacterizationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    @MockK private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @MockK private lateinit var appUsageRepository: AppUsageRepository
    @MockK private lateinit var hiddenAppsRepository: HiddenAppsRepository
    @MockK private lateinit var settingsRepository: SettingsRepository
    @MockK private lateinit var customNamesRepository: CustomNamesRepository

    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>
    private lateinit var hiddenAppsFlow: MutableStateFlow<Set<String>>
    private lateinit var sortOrderFlow: MutableStateFlow<SortOrder>
    private lateinit var customNamesFlow: MutableStateFlow<Map<String, String>>
    private lateinit var usageSnapshotFlow: MutableStateFlow<Map<String, List<Long>>>

    private lateinit var useCase: GetDrawerAppsUseCase

    private val appA = AppInfo("Apple", "Apple", "com.apple", "Main")
    private val appM = AppInfo("Mango", "Mango", "com.mango", "Main")
    private val appZ = AppInfo("Zebra", "Zebra", "com.zebra", "Main")

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
            dispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    /**
     * The pin that matters most: a **reverse-ordered** raw list still comes out
     * fully alphabetical. This is exactly what protects the drawer when the holder
     * stops pre-sorting.
     */
    @Test
    fun `drawer output is alphabetical no matter the raw order`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }
        try {
            advanceUntilIdle()

            // Deliberately worst-case: reverse of the expected order.
            rawAppsFlow.value = listOf(appZ, appM, appA)
            advanceUntilIdle()

            assertEquals(
                listOf("Apple", "Mango", "Zebra"),
                results.last().map { it.displayName },
            )
        } finally {
            job.cancel()
        }
    }

    /**
     * Reordering the SAME set in the raw holder must NOT churn a new drawer
     * emission: the consumer sorts to an identical list and the terminal
     * `distinctUntilChanged` collapses it. Pins that a raw-holder reorder (possible
     * once `getActivityList` order is no longer normalized by a central sort) is
     * invisible downstream.
     */
    @Test
    fun `reordering the same raw set emits no new drawer list`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }
        try {
            advanceUntilIdle()

            rawAppsFlow.value = listOf(appA, appM, appZ)
            advanceUntilIdle()
            val countAfterFirst = results.size

            // Same three apps, different order — semantically identical drawer.
            rawAppsFlow.value = listOf(appZ, appA, appM)
            advanceUntilIdle()

            assertEquals(
                countAfterFirst,
                results.size,
                "a pure reorder of the same set must not produce a new emission",
            )
            assertEquals(
                listOf("Apple", "Mango", "Zebra"),
                results.last().map { it.displayName },
            )
        } finally {
            job.cancel()
        }
    }

    /**
     * Custom-name folding AND sorting both live at the consumer now. Pins that a
     * rename re-sorts to the new display name (Zebra → "Aardvark" jumps to the
     * front), from an unsorted raw list.
     */
    @Test
    fun `custom name is applied then sorted at the consumer`() = runTest {
        val results = mutableListOf<List<AppInfo>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.drawerApps.collect { results.add(it) }
        }
        try {
            advanceUntilIdle()

            rawAppsFlow.value = listOf(appZ, appM, appA)
            // customNames is keyed by packageName (see applyCustomNames).
            customNamesFlow.value = mapOf(appZ.packageName to "Aardvark")
            advanceUntilIdle()

            assertEquals(
                listOf("Aardvark", "Apple", "Mango"),
                results.last().map { it.displayName },
            )
        } finally {
            job.cancel()
        }
    }
}
