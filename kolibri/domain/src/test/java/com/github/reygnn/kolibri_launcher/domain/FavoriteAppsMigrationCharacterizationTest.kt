package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.service.ComponentLabelResolver
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
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
import kotlin.test.assertTrue

/**
 * GOLDEN-MASTER characterization for the `AppInfo.isFavorite` removal
 * (docs/EXECUTION_SHARED_INSTALLED_APPS.md, Stage-0 prerequisite #1).
 *
 * WHY THIS EXISTS. The Phase-1 canonicalization drops `AppInfo.isFavorite` and
 * moves favorite membership entirely into `GetFavoriteAppsUseCase` /
 * `FavoritesRepository`. To prove that sweep preserves behaviour, this test pins
 * the **observable** favorites output — WHICH components are returned, in WHAT
 * order, and the `isFallback` flag — and deliberately does NOT assert on
 * `AppInfo.isFavorite` (that is the mechanism being removed). Run GREEN against
 * `main`, then GREEN after the flag is gone: same components, same order, same
 * fallback = no behavioural regression.
 *
 * Scope: the authoritative path only (raw list non-empty from the start), so the
 * cold-start provisional first-paint via [ComponentLabelResolver] is not exercised
 * — `resolveLabel` is stubbed but unreached. The provisional path has its own
 * dedicated tests.
 *
 * MIGRATION NOTE. On migration update the `AppInfo` import to
 * `com.github.reygnn.launcher.core`; the constructors below pass no `isFavorite`
 * already, so only the import line changes. Assertions are unchanged.
 *
 * Convention: single dispatcher via [MainDispatcherRule]; no separate TestScope /
 * StandardTestDispatcher (see TESTING_CONVENTIONS.kt).
 */
@ExperimentalCoroutinesApi
class FavoriteAppsMigrationCharacterizationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    @MockK private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @MockK private lateinit var favoritesRepository: FavoritesRepository
    @MockK private lateinit var favoritesOrderRepository: FavoritesOrderRepository
    @MockK private lateinit var hiddenAppsRepository: HiddenAppsRepository
    @MockK private lateinit var customNamesRepository: CustomNamesRepository
    @MockK private lateinit var componentLabelResolver: ComponentLabelResolver

    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>
    private lateinit var favoritesFlow: MutableStateFlow<Set<String>>
    private lateinit var hiddenAppsFlow: MutableStateFlow<Set<String>>
    private lateinit var orderFlow: MutableStateFlow<List<String>>
    private lateinit var customNamesFlow: MutableStateFlow<Map<String, String>>

    private lateinit var useCase: GetFavoriteAppsUseCase

    private val appA = AppInfo("Apple", "Apple", "com.apple", "Main")
    private val appM = AppInfo("Mango", "Mango", "com.mango", "Main")
    private val appZ = AppInfo("Zebra", "Zebra", "com.zebra", "Main")
    private val allApps = listOf(appZ, appM, appA) // deliberately unsorted

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        rawAppsFlow = MutableStateFlow(allApps)
        favoritesFlow = MutableStateFlow(emptySet())
        hiddenAppsFlow = MutableStateFlow(emptySet())
        orderFlow = MutableStateFlow(emptyList())
        customNamesFlow = MutableStateFlow(emptyMap())

        every { installedAppsStateRepository.rawAppsFlow } returns rawAppsFlow
        every { favoritesRepository.favoriteComponentsFlow } returns favoritesFlow
        every { hiddenAppsRepository.hiddenAppsFlow } returns hiddenAppsFlow
        every { favoritesOrderRepository.favoriteComponentsOrderFlow } returns orderFlow
        every { customNamesRepository.customNamesFlow } returns customNamesFlow

        // Real sort semantics: honor saved order, then the rest alphabetically.
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } answers {
            val apps = firstArg<List<AppInfo>>()
            val order = secondArg<List<String>>()
            if (order.isEmpty()) {
                apps.sortedBy { it.displayName.lowercase() }
            } else {
                val byKey = apps.associateBy { it.componentName }
                val head = order.mapNotNull { byKey[it] }
                val tail = apps.filter { it.componentName !in order }
                    .sortedBy { it.displayName.lowercase() }
                head + tail
            }
        }
        // Unreached on the authoritative path, present so init never NPEs.
        coEvery { componentLabelResolver.resolveLabel(any()) } returns null

        useCase = GetFavoriteAppsUseCase(
            installedAppsStateRepository,
            favoritesRepository,
            favoritesOrderRepository,
            hiddenAppsRepository,
            customNamesRepository,
            componentLabelResolver,
            dispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    /** Favorites present → exactly those components, alphabetical (no saved order), not fallback. */
    @Test
    fun `favorites are the chosen components sorted alphabetically`() = runTest {
        val results = mutableListOf<UiState<FavoriteAppsResult>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.favoriteApps.collect { results.add(it) }
        }
        try {
            favoritesFlow.value = setOf(appZ.componentName, appA.componentName)
            advanceUntilIdle()

            val result = (results.last() as UiState.Success).data
            assertEquals(
                listOf(appA.componentName, appZ.componentName),
                result.apps.map { it.componentName },
            )
            assertTrue(!result.isFallback, "real favorites must not be a fallback")
        } finally {
            job.cancel()
        }
    }

    /** Saved order wins over alphabetical for the components it lists. */
    @Test
    fun `saved order is honored`() = runTest {
        val results = mutableListOf<UiState<FavoriteAppsResult>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.favoriteApps.collect { results.add(it) }
        }
        try {
            favoritesFlow.value = setOf(appA.componentName, appZ.componentName)
            orderFlow.value = listOf(appZ.componentName, appA.componentName) // Z before A
            advanceUntilIdle()

            val result = (results.last() as UiState.Success).data
            assertEquals(
                listOf(appZ.componentName, appA.componentName),
                result.apps.map { it.componentName },
            )
        } finally {
            job.cancel()
        }
    }

    /**
     * No favorites → fallback = top-N visible apps, alphabetical, `isFallback=true`.
     * Pins the fallback branch that also depends on the (now consumer-side) sort.
     */
    @Test
    fun `no favorites yields alphabetical fallback`() = runTest {
        val results = mutableListOf<UiState<FavoriteAppsResult>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.favoriteApps.collect { results.add(it) }
        }
        try {
            favoritesFlow.value = emptySet()
            advanceUntilIdle()

            val result = (results.last() as UiState.Success).data
            assertTrue(result.isFallback, "empty favorites must fall back")
            assertEquals(
                listOf(appA.componentName, appM.componentName, appZ.componentName),
                result.apps.map { it.componentName },
            )
        } finally {
            job.cancel()
        }
    }
}
