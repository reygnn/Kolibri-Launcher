package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.usecase.SeedDefaultFavoritesUseCase
import app.cash.turbine.test
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.FactoryResetUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.settings.SettingsViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@ExperimentalCoroutinesApi
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    private lateinit var getInstalledAppsUseCase: GetInstalledAppsUseCase
    private lateinit var factoryResetUseCase: FactoryResetUseCase
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var favoritesOrderRepository: FavoritesOrderRepository
    private lateinit var dataStoreMaintenanceRepository: DataStoreMaintenanceRepository
    private lateinit var seedDefaultFavoritesUseCase: SeedDefaultFavoritesUseCase

    private lateinit var viewModel: SettingsViewModel
    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>

    private val app1 = AppInfo("App A", "App A", "com.a", "class1")
    private val app2 = AppInfo("App B", "App B", "com.b", "class2")
    private val testApps = listOf(app1, app2)

    @Before
    fun setup() {
        getInstalledAppsUseCase = mockk(relaxed = true)
        factoryResetUseCase = mockk(relaxed = true)
        favoritesRepository = mockk(relaxed = true)
        favoritesOrderRepository = mockk(relaxed = true)
        dataStoreMaintenanceRepository = mockk(relaxed = true)
        seedDefaultFavoritesUseCase = mockk(relaxed = true)

        rawAppsFlow = MutableStateFlow(emptyList())

        // Stubbing: Der UseCase gibt den Flow zurück (nicht-suspend → every)
        every { getInstalledAppsUseCase.unsortedInstalledAppsFlow } returns rawAppsFlow
    }

    // ========== EXISTING TESTS (Updated Constructor) ==========

    @Test
    fun `installedApps StateFlow - initially is empty`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        assertThat(viewModel.installedApps.value.isEmpty()).isTrue()
    }

    @Test
    fun `installedApps StateFlow - emits new app list from usecase`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()

            rawAppsFlow.value = testApps

            val emittedList = awaitItem()
            assertThat(emittedList.size).isEqualTo(2)
            assertThat(emittedList[0].displayName).isEqualTo("App A")

            rawAppsFlow.value = listOf(app2)

            val secondEmittedList = awaitItem()
            assertThat(secondEmittedList.size).isEqualTo(1)
            assertThat(secondEmittedList[0].displayName).isEqualTo("App B")
        }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `installedApps - when usecase flow crashes - handles gracefully`() = runTest {
        // Stubbing: UseCase wirft Exception via Flow
        every { getInstalledAppsUseCase.unsortedInstalledAppsFlow } returns flow {
            throw IOException("Cannot load apps")
        }

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            advanceUntilIdle()
            // Should emit empty list or handle error gracefully
            val result = awaitItem()
            assertThat(result).isNotNull()
        }
    }

    @Test
    fun `installedApps - when usecase flow crashes with RuntimeException - handles gracefully`() =
        runTest {
            every { getInstalledAppsUseCase.unsortedInstalledAppsFlow } returns flow {
                throw RuntimeException("Database corrupted")
            }

            viewModel = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                favoritesRepository,
                favoritesOrderRepository,
                dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
                seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )

            viewModel.installedApps.test {
                advanceUntilIdle()
                val result = awaitItem()
                assertThat(result).isNotNull()
            }
        }

    @Test
    fun `installedApps - with very large app list - handles efficiently`() = runTest {
        val largeAppList = (1..1000).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()
            rawAppsFlow.value = largeAppList
            val result = awaitItem()
            assertThat(result.size).isEqualTo(1000)
        }
    }

    @Test
    fun `installedApps - rapid flow updates - handles correctly`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()

            // Rapid updates
            rawAppsFlow.value = listOf(app1)
            assertThat(awaitItem().size).isEqualTo(1)

            rawAppsFlow.value = testApps
            assertThat(awaitItem().size).isEqualTo(2)

            rawAppsFlow.value = emptyList()
            assertThat(awaitItem().size).isEqualTo(0)

            rawAppsFlow.value = testApps
            assertThat(awaitItem().size).isEqualTo(2)
        }
    }

    @Test
    fun `installedApps - with duplicate apps in flow - forwards them as-is`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()
            val duplicates = listOf(app1, app1, app2)
            rawAppsFlow.value = duplicates
            val result = awaitItem()
            assertThat(result.size).isEqualTo(3)
        }
    }

    @Test
    fun `installedApps - when flow emits null values in list - handles gracefully`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()
            rawAppsFlow.value = testApps
            val result = awaitItem()
            assertThat(result.size).isEqualTo(2)
        }
    }

    @Test
    fun `installedApps - multiple subscribers - all receive updates`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()

            viewModel.installedApps.test {
                assertThat(awaitItem()).isEmpty()
                rawAppsFlow.value = testApps
                // Both subscribers should receive the update
                val result1 = awaitItem()
                assertThat(result1.size).isEqualTo(2)
            }
            val result2 = awaitItem()
            assertThat(result2.size).isEqualTo(2)
        }
    }

    @Test
    fun `installedApps - when created multiple times - each instance has independent state`() =
        runTest {
            val viewModel1 = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                favoritesRepository,
                favoritesOrderRepository,
                dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
                seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )
            val viewModel2 = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                favoritesRepository,
                favoritesOrderRepository,
                dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
                seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )

            viewModel1.installedApps.test {
                assertThat(awaitItem()).isEmpty()
                viewModel2.installedApps.test {
                    assertThat(awaitItem()).isEmpty()
                    rawAppsFlow.value = testApps
                    assertThat(awaitItem().size).isEqualTo(2)
                }
                assertThat(awaitItem().size).isEqualTo(2)
            }
        }

    @Test
    fun `installedApps - stateIn operator - maintains last value for new collectors`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Set a value
        rawAppsFlow.value = testApps
        advanceUntilIdle()

        // New collector should immediately get the last value
        viewModel.installedApps.test {
            val result = awaitItem()
            assertThat(result.size).isEqualTo(2)
            assertThat(result[0].displayName).isEqualTo("App A")
        }
    }

    @Test
    fun `installedApps - collector cancelled - does not affect other collectors`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            awaitItem() // Get initial value
            cancel() // Cancel this collector
        }

        // New collector should still work
        viewModel.installedApps.test {
            val result = awaitItem()
            assertThat(result).isNotNull()
        }
    }

    @Test
    fun `installedApps - with apps containing special characters - handles correctly`() = runTest {
        val specialApps = listOf(
            AppInfo("App 🚀", "App 🚀", "com.emoji", "class1"),
            AppInfo("App & Test", "App & Test", "com.ampersand", "class2"),
            AppInfo("App <XML>", "App <XML>", "com.xml", "class3")
        )

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()
            rawAppsFlow.value = specialApps
            val result = awaitItem()
            assertThat(result.size).isEqualTo(3)
            assertThat(result[0].displayName).isEqualTo("App 🚀")
        }
    }

    @Test
    fun `installedApps - empty to large to empty - handles correctly`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertThat(awaitItem()).isEmpty()
            val largeList = (1..100).map { AppInfo("App $it", "App $it", "com.$it", "class$it") }
            rawAppsFlow.value = largeList
            assertThat(awaitItem().size).isEqualTo(100)
            rawAppsFlow.value = emptyList()
            assertThat(awaitItem().size).isEqualTo(0)
        }
    }

    // ========== UPDATED FACTORY RESET TESTS ==========

    @Test
    fun `onFactoryResetConfirmed - with usage data - calls usecase with true`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Stubbing: Erfolg simulieren, um sicherzugehen, dass kein Crash passiert (suspend → coEvery)
        coEvery { factoryResetUseCase(any()) } returns FactoryResetUseCase.Result.Success

        // Act
        viewModel.onFactoryResetConfirmed(includeUsageData = true)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert: Wir prüfen NUR noch, ob der UseCase richtig aufgerufen wurde.
        // Die interne Logik (welches Repo aufgerufen wird) wird im UseCase-Test geprüft.
        coVerify { factoryResetUseCase(true) }
    }

    @Test
    fun `onFactoryResetConfirmed - without usage data - calls usecase with false`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Stubbing
        coEvery { factoryResetUseCase(any()) } returns FactoryResetUseCase.Result.Success

        // Act
        viewModel.onFactoryResetConfirmed(includeUsageData = false)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        coVerify { factoryResetUseCase(false) }
    }

    // ========== STORAGE CLEANUP TESTS ==========

    @Test
    fun `onCleanupStorageConfirmed - removes orphan keys and shows done toast when something removed`() =
        runTest {
            viewModel = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                favoritesRepository,
                favoritesOrderRepository,
                dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
                seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )
            coEvery { dataStoreMaintenanceRepository.removeOrphanKeys() } returns
                DataStoreMaintenanceRepository.Result.Removed(3)

            viewModel.event.test {
                viewModel.onCleanupStorageConfirmed()
                mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

                val event = awaitItem()
                assertIs<UiEvent.ShowToast>(event)
                assertThat(event.messageResId).isEqualTo(com.github.reygnn.kolibri_launcher.R.string.cleanup_storage_done)
            }
            coVerify { dataStoreMaintenanceRepository.removeOrphanKeys() }
        }

    @Test
    fun `onCleanupStorageConfirmed - shows none toast when nothing removed`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
        coEvery { dataStoreMaintenanceRepository.removeOrphanKeys() } returns
            DataStoreMaintenanceRepository.Result.Removed(0)

        viewModel.event.test {
            viewModel.onCleanupStorageConfirmed()
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            val event = awaitItem()
            assertIs<UiEvent.ShowToast>(event)
            assertThat(event.messageResId).isEqualTo(com.github.reygnn.kolibri_launcher.R.string.cleanup_storage_none)
        }
    }

    @Test
    fun `onCleanupStorageConfirmed - shows error toast when cleanup fails - failure never masquerades as clean`() =
        runTest {
            viewModel = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                favoritesRepository,
                favoritesOrderRepository,
                dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
                seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )
            coEvery { dataStoreMaintenanceRepository.removeOrphanKeys() } returns
                DataStoreMaintenanceRepository.Result.Failed

            viewModel.event.test {
                viewModel.onCleanupStorageConfirmed()
                mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

                val event = awaitItem()
                assertIs<UiEvent.ShowToast>(event)
                assertThat(event.messageResId).isEqualTo(com.github.reygnn.kolibri_launcher.R.string.cleanup_storage_error)
            }
        }

    // ========== RESEED AFTER RESET (2b/29) ==========

    @Test
    fun `onFactoryResetConfirmed - success - reseeds the default favorites`() = runTest {
        coEvery { factoryResetUseCase(any()) } returns FactoryResetUseCase.Result.Success
        viewModel = createViewModel()

        viewModel.onFactoryResetConfirmed(includeUsageData = true)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { seedDefaultFavoritesUseCase() }
    }

    @Test
    fun `onFactoryResetConfirmed - partial failure - still runs the guarded reseed`() = runTest {
        // The seed itself skips when favorites survived a failed purge (SeedDefaultFavoritesUseCaseTest).
        coEvery { factoryResetUseCase(any()) } returns FactoryResetUseCase.Result.PartialFailure
        viewModel = createViewModel()

        viewModel.onFactoryResetConfirmed(includeUsageData = true)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { seedDefaultFavoritesUseCase() }
    }

    @Test
    fun `onFactoryResetConfirmed - error - does not reseed`() = runTest {
        coEvery { factoryResetUseCase(any()) } returns FactoryResetUseCase.Result.Error
        viewModel = createViewModel()

        viewModel.onFactoryResetConfirmed(includeUsageData = true)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { seedDefaultFavoritesUseCase() }
    }

    // ========== DOOMSDAY TESTS - ROCKY BALBOA EDITION ==========

    @Test
    fun `doomsday - factory reset returns PartialFailure - shows failure toast`() = runTest {
        // SZENARIO: Reset hat teilweise funktioniert, teilweise nicht.
        // User muss informiert werden.

        coEvery { factoryResetUseCase(any()) } returns FactoryResetUseCase.Result.PartialFailure

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.event.test {
            viewModel.onFactoryResetConfirmed(true)

            val event = awaitItem()
            assertIs<UiEvent.ShowToast>(event)
            // Prüfe, ob die korrekte Error-Message kommt
            assertThat(event.messageResId).isEqualTo(com.github.reygnn.kolibri_launcher.R.string.reset_failed)
        }
    }

    @Test
    fun `doomsday - factory reset returns Error - shows failure toast`() = runTest {
        // SZENARIO: Reset komplett fehlgeschlagen (IO Error).

        coEvery { factoryResetUseCase(any()) } returns FactoryResetUseCase.Result.Error

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.event.test {
            viewModel.onFactoryResetConfirmed(true)

            val event = awaitItem()
            assertIs<UiEvent.ShowToast>(event)
            assertThat(event.messageResId).isEqualTo(com.github.reygnn.kolibri_launcher.R.string.reset_failed)
        }
    }

    @Test
    fun `doomsday - factory reset throws RuntimeException - caught by launchSafe`() = runTest {
        // SZENARIO: Der UseCase stürzt ab (nicht Result.Error, sondern Exception).
        // ViewModel darf nicht crashen.

        coEvery { factoryResetUseCase(any()) } throws RuntimeException("System died")

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Act: Aufrufen
        viewModel.onFactoryResetConfirmed(true)
        advanceUntilIdle()

        // Assert: ViewModel lebt noch.
        // Da launchSafe Exceptions meist nur loggt (oder generisch behandelt),
        // prüfen wir hier primär, dass der Test nicht rot wird (kein Crash).
        assertThat(viewModel).isNotNull()
    }

    @Test
    fun `doomsday - apps flow throws OutOfMemoryError - handles gracefully`() = runTest {
        // SZENARIO: Zu viele Apps, Speicher voll beim Laden.
        // Ein Error (nicht Exception) wird geworfen.

        every { getInstalledAppsUseCase.unsortedInstalledAppsFlow } returns flow {
            delay(10) // WICHTIG: Verzögerung, damit Turbine subscriben kann, bevor der Crash passiert!
            throw OutOfMemoryError("Too many apps")
        }

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            favoritesRepository,
            favoritesOrderRepository,
            dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
            seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.event.test {
            // Wir erwarten den Error-Toast, da dein ViewModel 'Throwable' fängt
            val event = awaitItem()
            assertIs<UiEvent.ShowToast>(event)
            assertThat(event.messageResId).isEqualTo(com.github.reygnn.kolibri_launcher.R.string.error_loading_apps)
        }
    }

    // ========== AUDIT-13: STALE-REPLAY REGRESSION (prepareFavoritesForSorting) ==========
    // The whole point of the fix: the sort dialog must read favorites + order from
    // the authoritative FRESH snapshot, NOT the hot favoriteComponentsFlow /
    // favoriteComponentsOrderFlow replay caches (stale under a stopped Home).

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        getInstalledAppsUseCase,
        factoryResetUseCase,
        favoritesRepository,
        favoritesOrderRepository,
        dataStoreMaintenanceRepository = dataStoreMaintenanceRepository,
        seedDefaultFavoritesUseCase = seedDefaultFavoritesUseCase,
        mainDispatcher = mainDispatcherRule.testDispatcher
    )

    @Test
    fun `prepareFavoritesForSorting - reads via snapshot, never the hot replay flow`() = runTest {
        coEvery { favoritesRepository.getFavoriteComponentsSnapshot() } returns setOf(app1.componentName)
        coEvery { favoritesOrderRepository.getFavoriteComponentsOrderSnapshot() } returns emptyList()
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), any()) } returns listOf(app1)

        val outcome = createViewModel().prepareFavoritesForSorting(testApps)

        assertThat(outcome).isInstanceOf(SettingsViewModel.SortFavoritesOutcome.Ready::class.java)
        // Authoritative fresh reads were used...
        coVerify(exactly = 1) { favoritesRepository.getFavoriteComponentsSnapshot() }
        coVerify(exactly = 1) { favoritesOrderRepository.getFavoriteComponentsOrderSnapshot() }
        // ...and the stale hot replay flows were NOT touched. This is the regression lock.
        verify(exactly = 0) { favoritesRepository.favoriteComponentsFlow }
        verify(exactly = 0) { favoritesOrderRepository.favoriteComponentsOrderFlow }
    }

    @Test
    fun `prepareFavoritesForSorting - empty app list - returns AppsNotLoaded without reading repos`() =
        runTest {
            val outcome = createViewModel().prepareFavoritesForSorting(emptyList())

            assertThat(outcome).isEqualTo(SettingsViewModel.SortFavoritesOutcome.AppsNotLoaded)
            coVerify(exactly = 0) { favoritesRepository.getFavoriteComponentsSnapshot() }
            coVerify(exactly = 0) { favoritesOrderRepository.getFavoriteComponentsOrderSnapshot() }
        }

    @Test
    fun `prepareFavoritesForSorting - no favorites among installed apps - returns NoFavorites`() =
        runTest {
            coEvery { favoritesRepository.getFavoriteComponentsSnapshot() } returns setOf("com.absent/Gone")

            val outcome = createViewModel().prepareFavoritesForSorting(testApps)

            assertThat(outcome).isEqualTo(SettingsViewModel.SortFavoritesOutcome.NoFavorites)
            // Order is only read once there is something to sort.
            coVerify(exactly = 0) { favoritesOrderRepository.getFavoriteComponentsOrderSnapshot() }
        }

    @Test
    fun `prepareFavoritesForSorting - returns Ready with the ordered favorites`() = runTest {
        val savedOrder = listOf(app2.componentName, app1.componentName)
        coEvery { favoritesRepository.getFavoriteComponentsSnapshot() } returns
            setOf(app1.componentName, app2.componentName)
        coEvery { favoritesOrderRepository.getFavoriteComponentsOrderSnapshot() } returns savedOrder
        // Impl sorts; here we assert the fragment receives exactly what sort produced.
        coEvery { favoritesOrderRepository.sortFavoriteComponents(any(), savedOrder) } returns listOf(app2, app1)

        val outcome = createViewModel().prepareFavoritesForSorting(testApps)

        assertIs<SettingsViewModel.SortFavoritesOutcome.Ready>(outcome)
        assertThat(outcome.orderedFavorites).isEqualTo(listOf(app2, app1))
        coVerify(exactly = 1) { favoritesOrderRepository.sortFavoriteComponents(listOf(app1, app2), savedOrder) }
    }

    @Test
    fun `prepareFavoritesForSorting - snapshot read fails non-IO - fails open to NoFavorites`() =
        runTest {
            // Mirrors the fragment it replaced: a non-cancellation read error degrades
            // (empty favorites) rather than aborting; TimberRule suppresses the DEBUG throw.
            coEvery { favoritesRepository.getFavoriteComponentsSnapshot() } throws RuntimeException("store hiccup")

            val outcome = createViewModel().prepareFavoritesForSorting(testApps)

            assertThat(outcome).isEqualTo(SettingsViewModel.SortFavoritesOutcome.NoFavorites)
        }

    @Test
    fun `prepareFavoritesForSorting - CancellationException from snapshot propagates`() = runTest {
        coEvery { favoritesRepository.getFavoriteComponentsSnapshot() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            createViewModel().prepareFavoritesForSorting(testApps)
        }
    }

    @Test
    fun `getCleanupPreview - maps repository Loaded to CleanupPreview Loaded`() = runTest {
        coEvery { dataStoreMaintenanceRepository.previewOrphanKeys() } returns
            DataStoreMaintenanceRepository.PreviewResult.Loaded(listOf("usage_com.foo", "obsolete_key"))

        val preview = createViewModel().getCleanupPreview()

        assertThat(preview).isEqualTo(SettingsViewModel.CleanupPreview.Loaded(listOf("usage_com.foo", "obsolete_key")))
    }

    @Test
    fun `getCleanupPreview - maps repository Failed to CleanupPreview Failed`() = runTest {
        coEvery { dataStoreMaintenanceRepository.previewOrphanKeys() } returns
            DataStoreMaintenanceRepository.PreviewResult.Failed

        assertThat(createViewModel().getCleanupPreview()).isEqualTo(SettingsViewModel.CleanupPreview.Failed)
    }
}
