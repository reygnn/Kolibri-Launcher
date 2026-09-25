package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.Purgeable
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.launcher.core.SyncInstalledAppsToHolder
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The use case no longer reconciles (auto-prunes) any store: stored user
 * assignments are kept and validated lazily at the point of use (the
 * Windows-shortcut model, root TODO.md). Its remaining job is purely to load,
 * keep-last-good on failure, update the central state, and emit an
 * [AppLoadResult]. The no-prune guarantee is now STRUCTURAL — the use case has
 * no store dependencies at all — so there is nothing store-related to assert
 * here; the lazy "missing" handling is pinned at the UI / GetFavoriteAppsUseCase
 * level instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveInstalledAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var installedAppsRepository: FakeInstalledAppsRepository
    private lateinit var installedAppsStateRepository: FakeInstalledAppsStateRepository
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
        useCase = ObserveInstalledAppsUseCase(
            SyncInstalledAppsToHolder(installedAppsRepository, installedAppsStateRepository),
        )
    }

    // =========================================================================
    // Erfolgsfall
    // =========================================================================

    @Test
    fun `invoke emits Success when apps are loaded`() = runTest {
        installedAppsRepository.installedApps = testApps

        useCase().test {
            val result = awaitItem()
            assertThat(result).isEqualTo(AppLoadResult.Success)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invoke updates state repository with loaded apps`() = runTest {
        installedAppsRepository.installedApps = testApps

        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(installedAppsStateRepository.rawAppsFlow.value).isEqualTo(testApps)
    }

    // =========================================================================
    // Leere Liste
    // =========================================================================

    @Test
    fun `invoke with empty list updates state to empty without error`() = runTest {
        installedAppsRepository.installedApps = emptyList()

        useCase().test {
            // Kein Error-Event erwartet, Flow sollte einfach enden
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(installedAppsStateRepository.rawAppsFlow.value).isEmpty()
    }

    // =========================================================================
    // Fehler mit Cache
    // =========================================================================

    @Test
    fun `invoke uses cached apps on error when cache exists`() = runTest {
        // Cache vorhanden
        installedAppsStateRepository.updateApps(testApps)

        val errorRepository = FailingInstalledAppsRepository(
            RuntimeException("Database error")
        )
        val useCaseWithError = ObserveInstalledAppsUseCase(
            SyncInstalledAppsToHolder(errorRepository, installedAppsStateRepository),
        )

        useCaseWithError().test {
            awaitComplete()  // Flow endet OHNE Error-Event
        }

        // State bleibt auf Cache
        assertThat(installedAppsStateRepository.rawAppsFlow.value).isEqualTo(testApps)
    }

    @Test
    fun `invoke does not revive a stale list on failure after a genuinely empty load`() = runTest {
        // Belang B / IAL-INV-4: keep-last-good lives in ONE home (the state holder).
        // On Failed the use case writes NOTHING, so the holder keeps its last emitted
        // state. Here a genuine Loaded(empty) precedes the Failed, so the drawer must
        // stay empty — the Commit-1 updateApps(cachedApps) re-write would wrongly
        // revive the stale non-empty list from the holder's point-read cache.
        val sequencedRepository = object : InstalledAppsRepository {
            override fun getInstalledApps(): Flow<AppLoad> = flowOf(
                AppLoad.Loaded(testApps),
                AppLoad.Loaded(emptyList()),
                AppLoad.Failed(RuntimeException("load glitch after empty"))
            )
            override suspend fun triggerAppsUpdate() {}
            override suspend fun purgeRepository() {}
        }
        val useCaseWithSequence = ObserveInstalledAppsUseCase(
            SyncInstalledAppsToHolder(sequencedRepository, installedAppsStateRepository),
        )

        useCaseWithSequence().test {
            // Loaded(testApps) → Success; Loaded(empty) → no emit; Failed (cache in
            // the point-read backing) → no error emit, no re-write.
            assertThat(awaitItem()).isEqualTo(AppLoadResult.Success)
            awaitComplete()
        }

        // Holder kept the genuinely-empty state; the stale list was NOT revived.
        assertThat(installedAppsStateRepository.rawAppsFlow.value).isEmpty()
    }

    // =========================================================================
    // Fehler ohne Cache
    // =========================================================================

    @Test
    fun `invoke emits Error when no cache exists on failure`() = runTest {
        // Kein Cache
        val errorRepository = FailingInstalledAppsRepository(
            RuntimeException("Database error")
        )
        val useCaseWithError = ObserveInstalledAppsUseCase(
            SyncInstalledAppsToHolder(errorRepository, installedAppsStateRepository),
        )

        useCaseWithError().test {
            val result = awaitItem()
            assertThat(result).isEqualTo(AppLoadResult.Error(AppLoadResult.Failure.NotLoaded))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Test-Hilfsklassen
    // =========================================================================

    /**
     * Repository that EMITS a load failure as [AppLoad.Failed] — it does NOT throw.
     * This mirrors the impl contract (IAL-INV-7): the real loader catches its own
     * errors and yields Failed as a value rather than propagating an exception. The
     * old throw-based fake was exactly the green-wash pattern this refactor removes.
     */
    private class FailingInstalledAppsRepository(
        private val cause: Throwable
    ) : InstalledAppsRepository, Purgeable {

        override fun getInstalledApps(): Flow<AppLoad> = flowOf(AppLoad.Failed(cause))

        override suspend fun triggerAppsUpdate() {}

        override suspend fun purgeRepository() {}
    }
}
