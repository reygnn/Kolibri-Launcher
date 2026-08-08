package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakePackagePresence
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveInstalledAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var installedAppsRepository: FakeInstalledAppsRepository
    private lateinit var installedAppsStateRepository: FakeInstalledAppsStateRepository
    private lateinit var favoritesRepository: TestFakeFavoritesRepository
    private lateinit var swipeActionsRepository: FakeSwipeActionsRepository
    private lateinit var hiddenAppsRepository: FakeHiddenAppsRepository
    private lateinit var customNamesRepository: FakeCustomNamesRepository
    private lateinit var packagePresence: FakePackagePresence
    private lateinit var useCase: ObserveInstalledAppsUseCase

    private val testApps = listOf(
        AppInfo("App1", "App1", "com.app1", "com.app1.Main"),
        AppInfo("App2", "App2", "com.app2", "com.app2.Main"),
        AppInfo("App3", "App3", "com.app3", "com.app3.Main")
    )

    @Before
    fun setup() {
        installedAppsRepository = FakeInstalledAppsRepository()
        installedAppsStateRepository = FakeInstalledAppsStateRepository()
        favoritesRepository = TestFakeFavoritesRepository()
        swipeActionsRepository = FakeSwipeActionsRepository()
        hiddenAppsRepository = FakeHiddenAppsRepository()
        customNamesRepository = FakeCustomNamesRepository()
        packagePresence = FakePackagePresence()
        useCase = ObserveInstalledAppsUseCase(
            installedAppsRepository,
            installedAppsStateRepository,
            favoritesRepository,
            swipeActionsRepository,
            hiddenAppsRepository,
            customNamesRepository,
            packagePresence
        )
    }

    // =========================================================================
    // Erfolgsfall
    // =========================================================================

    @Test
    fun `invoke emits Success when apps are loaded`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = testApps

        // Act & Assert
        useCase().test {
            val result = awaitItem()
            assertThat(result).isEqualTo(AppLoadResult.Success)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invoke updates state repository with loaded apps`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = testApps

        // Act
        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert
        assertThat(installedAppsStateRepository.rawAppsFlow.value).isEqualTo(testApps)
    }

    // =========================================================================
    // Leere Liste
    // =========================================================================

    @Test
    fun `invoke with empty list updates state to empty without error`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = emptyList()

        // Act
        useCase().test {
            // Kein Error-Event erwartet, Flow sollte einfach enden
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert
        assertThat(installedAppsStateRepository.rawAppsFlow.value).isEmpty()
    }

    // =========================================================================
    // Favoriten-Cleanup
    // =========================================================================

    @Test
    fun `invoke calls reconcileFavoriteComponents with correct componentNames`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = testApps

        // Act
        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert
        val expectedComponentNames = testApps.map { it.componentName }
        assertThat(favoritesRepository.lastCleanupComponentNames)
            .containsExactlyElementsIn(expectedComponentNames)
    }

    @Test
    fun `invoke does not call cleanup when app list is empty`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = emptyList()

        // Act
        useCase().test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert
        assertThat(favoritesRepository.cleanupCallCount).isEqualTo(0)
    }

    // =========================================================================
    // Load-time reconciliation of swipe / hidden / custom names (TODO §24)
    // =========================================================================

    @Test
    fun `invoke reconciles swipe hidden and custom names against loaded apps`() = runTest {
        // Arrange: one valid assignment (present in testApps) + one orphan each.
        val validComponent = testApps[0].componentName // com.app1/com.app1.Main
        val orphanComponent = "com.gone/com.gone.Main"
        val validPackage = testApps[0].packageName      // com.app1
        val orphanPackage = "com.gone"

        swipeActionsRepository.swipeLeftApp = orphanComponent
        swipeActionsRepository.swipeRightApp = validComponent
        hiddenAppsRepository.hiddenApps = setOf(validComponent, orphanComponent)
        customNamesRepository.setCustomNameForPackage(validPackage, "Keep")
        customNamesRepository.setCustomNameForPackage(orphanPackage, "Drop")

        installedAppsRepository.installedApps = testApps

        // Act
        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert: orphans gone, valid entries kept.
        assertThat(swipeActionsRepository.swipeLeftApp).isNull()
        assertThat(swipeActionsRepository.swipeRightApp).isEqualTo(validComponent)
        assertThat(hiddenAppsRepository.hiddenApps).containsExactly(validComponent)
        assertThat(customNamesRepository.getAllCustomNames().keys).containsExactly(validPackage)
    }

    @Test
    fun `partial load vetoes a still-present app but still removes a genuine orphan`() = runTest {
        // The core R-INV guarantee (RECONCILE_SPEC §3): the loaded list is only a
        // candidate finder, not ground truth. com.app2 is dropped from the load
        // but is actually still installed; com.gone is a genuine orphan. Both are
        // assigned across the stores. PackagePresence is the deletion gate.
        val stillInstalled = testApps[1].componentName   // com.app2/com.app2.Main
        val stillInstalledPkg = testApps[1].packageName  // com.app2
        val orphanComponent = "com.gone/com.gone.Main"
        val orphanPkg = "com.gone"

        favoritesRepository.saveFavoriteComponents(listOf(stillInstalled, orphanComponent))
        hiddenAppsRepository.hiddenApps = setOf(stillInstalled, orphanComponent)
        swipeActionsRepository.swipeLeftApp = stillInstalled
        swipeActionsRepository.swipeRightApp = orphanComponent
        customNamesRepository.setCustomNameForPackage(stillInstalledPkg, "Keep")
        customNamesRepository.setCustomNameForPackage(orphanPkg, "Drop")

        // Deletion gate: the dropped-but-installed app is present, the orphan is gone.
        packagePresence.presentComponents = setOf(stillInstalled)
        packagePresence.presentPackages = setOf(stillInstalledPkg)

        // Partial load: testApps minus com.app2, so stillInstalled is a candidate.
        installedAppsRepository.installedApps = listOf(testApps[0], testApps[2])

        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Vetoed (verified-present) assignments survive the partial load...
        assertThat(favoritesRepository.favoriteComponentsFlow.first()).contains(stillInstalled)
        assertThat(hiddenAppsRepository.hiddenApps).contains(stillInstalled)
        assertThat(swipeActionsRepository.swipeLeftApp).isEqualTo(stillInstalled)
        assertThat(customNamesRepository.getAllCustomNames()).containsKey(stillInstalledPkg)
        // ...while the genuine orphan is still removed (M3 covers favorites too).
        assertThat(favoritesRepository.favoriteComponentsFlow.first()).doesNotContain(orphanComponent)
        assertThat(hiddenAppsRepository.hiddenApps).doesNotContain(orphanComponent)
        assertThat(swipeActionsRepository.swipeRightApp).isNull()
        assertThat(customNamesRepository.getAllCustomNames()).doesNotContainKey(orphanPkg)
    }

    @Test
    fun `invoke isolates a failing cleanup - other stores still reconcile and state still updates`() = runTest {
        // The four cleanups share a try-block with updateApps + emit(Success). If
        // a store's cleanup weren't guarded independently (runCleanup), its throw
        // would land in the outer catch and SKIP updateApps + Success — so a
        // freshly installed/uninstalled app would never reach the drawer/home
        // over a transient DataStore hiccup. Pin that a failing favorites cleanup
        // is isolated: the other stores still reconcile, the state still updates,
        // and the load still reports Success.
        favoritesRepository.throwOnCleanup = RuntimeException("DataStore write failed")

        val validComponent = testApps[0].componentName // com.app1/com.app1.Main
        val orphanComponent = "com.gone/com.gone.Main"
        swipeActionsRepository.swipeLeftApp = orphanComponent
        hiddenAppsRepository.hiddenApps = setOf(validComponent, orphanComponent)
        customNamesRepository.setCustomNameForPackage("com.gone", "Drop")

        installedAppsRepository.installedApps = testApps

        useCase().test {
            // The failing favorites cleanup must NOT abort the load.
            assertThat(awaitItem()).isEqualTo(AppLoadResult.Success)
            cancelAndIgnoreRemainingEvents()
        }

        // Favorites cleanup was attempted (and threw)...
        assertThat(favoritesRepository.cleanupCallCount).isEqualTo(1)
        // ...the other three stores still reconciled despite it...
        assertThat(swipeActionsRepository.swipeLeftApp).isNull()
        assertThat(hiddenAppsRepository.hiddenApps).containsExactly(validComponent)
        assertThat(customNamesRepository.getAllCustomNames()).isEmpty()
        // ...and the freshly loaded list still reached the central state.
        assertThat(installedAppsStateRepository.getCurrentApps()).isEqualTo(testApps)
    }

    @Test
    fun `invoke does not reconcile swipe hidden custom names when app list is empty`() = runTest {
        // Cold-start guard: an empty load must NOT wipe assignments.
        swipeActionsRepository.swipeLeftApp = "com.gone/com.gone.Main"
        hiddenAppsRepository.hiddenApps = setOf("com.gone/com.gone.Main")
        customNamesRepository.setCustomNameForPackage("com.gone", "Drop")

        installedAppsRepository.installedApps = emptyList()

        useCase().test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // Untouched — the empty-list guard skips all cleanup.
        assertThat(swipeActionsRepository.swipeLeftApp).isEqualTo("com.gone/com.gone.Main")
        assertThat(hiddenAppsRepository.hiddenApps).containsExactly("com.gone/com.gone.Main")
        assertThat(customNamesRepository.getAllCustomNames().keys).containsExactly("com.gone")
    }

    // =========================================================================
    // Fehler mit Cache
    // =========================================================================

    @Test
    fun `invoke uses cached apps on error when cache exists`() = runTest {
        // Arrange: Cache vorhanden
        installedAppsStateRepository.updateApps(testApps)

        // Repository wirft Fehler
        val errorRepository = ErrorThrowingInstalledAppsRepository(
            RuntimeException("Database error")
        )
        val useCaseWithError = ObserveInstalledAppsUseCase(
            errorRepository,
            installedAppsStateRepository,
            favoritesRepository,
            swipeActionsRepository,
            hiddenAppsRepository,
            customNamesRepository,
            packagePresence
        )

        // Act & Assert
        useCaseWithError().test {
            awaitComplete()  // Flow endet OHNE Error-Event
        }

        // Assert: State bleibt auf Cache
        assertThat(installedAppsStateRepository.rawAppsFlow.value).isEqualTo(testApps)
    }

    // =========================================================================
    // Fehler ohne Cache
    // =========================================================================

    @Test
    fun `invoke emits Error when no cache exists on failure`() = runTest {
        // Arrange: Kein Cache
        val errorRepository = ErrorThrowingInstalledAppsRepository(
            RuntimeException("Database error")
        )
        val useCaseWithError = ObserveInstalledAppsUseCase(
            errorRepository,
            installedAppsStateRepository,
            favoritesRepository,
            swipeActionsRepository,
            hiddenAppsRepository,
            customNamesRepository,
            packagePresence
        )

        // Act & Assert
        useCaseWithError().test {
            val result = awaitItem()
            assertThat(result).isEqualTo(AppLoadResult.Error(AppLoadResult.Failure.NotLoaded))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Retry-Counter — across invocations
    // =========================================================================

    /**
     * Pins the regression: with the previous class-field counter that only
     * reset on success, a fully failed first invocation left the counter at
     * MAX_APP_LOAD_RETRIES; the second invocation's first retry then computed
     * `delay = base * (max + 1)` instead of `base * 1`, scaling the linear
     * backoff up across invocations. The counter is now a local var inside
     * `flow { … }`, so each invocation starts fresh.
     *
     * Verified via runTest's virtual time: both invocations consume the same
     * total duration. With the bug, the second would take ~2.5× as long
     * (delays of 4+5+6 = 15× base vs. 1+2+3 = 6× base).
     */
    @Test
    fun `retry counter resets between invocations on IOException backoff`() = runTest {
        val errorRepository = ErrorThrowingInstalledAppsRepository(IOException("boom"))
        val useCase = ObserveInstalledAppsUseCase(
            errorRepository,
            installedAppsStateRepository,
            favoritesRepository,
            swipeActionsRepository,
            hiddenAppsRepository,
            customNamesRepository,
            packagePresence,
        )

        val firstStart = testScheduler.currentTime
        useCase().test {
            val result = awaitItem()
            assertThat(result).isInstanceOf(AppLoadResult.Error::class.java)
            cancelAndIgnoreRemainingEvents()
        }
        val firstDuration = testScheduler.currentTime - firstStart

        val secondStart = testScheduler.currentTime
        useCase().test {
            val result = awaitItem()
            assertThat(result).isInstanceOf(AppLoadResult.Error::class.java)
            cancelAndIgnoreRemainingEvents()
        }
        val secondDuration = testScheduler.currentTime - secondStart

        assertThat(secondDuration).isEqualTo(firstDuration)
    }

    // =========================================================================
// Test-Hilfsklassen
// =========================================================================

    /**
     * Eigenständiges Fake das Cleanup-Aufrufe trackt.
     * (FakeFavoritesRepository ist final, daher eigene Implementierung)
     */
    private class TestFakeFavoritesRepository : FavoritesRepository, Purgeable {
        private val flow = MutableStateFlow(setOf<String>())

        var lastCleanupComponentNames: List<String>? = null
        var cleanupCallCount = 0

        /** When set, cleanup records the call and then throws it (I/O-failure sim). */
        var throwOnCleanup: Throwable? = null

        override val favoriteComponentsFlow = flow

        override suspend fun isFavoriteComponent(componentName: String?) =
            componentName in flow.value

        override suspend fun reconcileFavoriteComponents(
            installedComponentNames: List<String>,
            isStillPresent: suspend (String) -> Boolean,
        ) {
            cleanupCallCount++
            lastCleanupComponentNames = installedComponentNames
            throwOnCleanup?.let { throw it }
            // Real predicate-gated removal (RECONCILE_FIX_SPEC M3) so the favorites
            // veto branch is exercisable, not just call-tracked.
            val orphans = flow.value - installedComponentNames.toSet()
            val verifiedAbsent = orphans.filterTo(HashSet()) { !isStillPresent(it) }
            flow.value = flow.value - verifiedAbsent
        }

        override suspend fun toggleFavoriteComponent(componentName: String) = true
        override suspend fun addFavoriteComponent(componentName: String) = true
        override suspend fun removeFavoriteComponent(componentName: String) = true
        override suspend fun saveFavoriteComponents(componentNames: List<String>) {
            flow.value = componentNames.toSet()
        }

        override suspend fun getFavoriteComponentsSnapshot(): Set<String> = flow.value

        override suspend fun readFavoritesForEdit(): FavoritesEditRead =
            FavoritesEditRead.Loaded(flow.value)

        override suspend fun purgeRepository() {
            flow.value = emptySet()
            lastCleanupComponentNames = null
            cleanupCallCount = 0
        }
    }

    /**
     * Repository das sofort einen Fehler wirft.
     */
    private class ErrorThrowingInstalledAppsRepository(
        private val error: Throwable
    ) : InstalledAppsRepository, Purgeable {

        override fun getInstalledApps(): Flow<List<AppInfo>> = flow {
            throw error
        }

        override suspend fun triggerAppsUpdate() {}

        override suspend fun purgeRepository() {}
    }
}