package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
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
        useCase = ObserveInstalledAppsUseCase(
            installedAppsRepository,
            installedAppsStateRepository,
            favoritesRepository
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
    fun `invoke calls cleanupFavoriteComponents with correct componentNames`() = runTest {
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
            favoritesRepository
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
            favoritesRepository
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

        override val favoriteComponentsFlow = flow

        override suspend fun isFavoriteComponent(componentName: String?) =
            componentName in flow.value

        override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {
            cleanupCallCount++
            lastCleanupComponentNames = installedComponentNames
        }

        override suspend fun toggleFavoriteComponent(componentName: String) = true
        override suspend fun addFavoriteComponent(componentName: String) = true
        override suspend fun removeFavoriteComponent(componentName: String) = true
        override suspend fun saveFavoriteComponents(componentNames: List<String>) {
            flow.value = componentNames.toSet()
        }

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