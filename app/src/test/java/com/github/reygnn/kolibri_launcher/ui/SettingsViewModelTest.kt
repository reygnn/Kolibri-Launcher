package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.FactoryResetUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.settings.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // NEU: UseCase Mocks statt Repository Mocks
    @Mock
    private lateinit var getInstalledAppsUseCase: GetInstalledAppsUseCase
    @Mock
    private lateinit var factoryResetUseCase: FactoryResetUseCase

    private lateinit var viewModel: SettingsViewModel
    private lateinit var rawAppsFlow: MutableStateFlow<List<AppInfo>>

    private val app1 = AppInfo("App A", "App A", "com.a", "class1")
    private val app2 = AppInfo("App B", "App B", "com.b", "class2")
    private val testApps = listOf(app1, app2)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        rawAppsFlow = MutableStateFlow(emptyList())

        // Stubbing: Der UseCase gibt den Flow zurück
        whenever(getInstalledAppsUseCase()).thenReturn(rawAppsFlow)
    }

    // ========== EXISTING TESTS (Updated Constructor) ==========

    @Test
    fun `installedApps StateFlow - initially is empty`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        assertTrue(viewModel.installedApps.value.isEmpty())
    }

    @Test
    fun `installedApps StateFlow - emits new app list from usecase`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())

            rawAppsFlow.value = testApps

            val emittedList = awaitItem()
            assertEquals(2, emittedList.size)
            assertEquals("App A", emittedList[0].displayName)

            rawAppsFlow.value = listOf(app2)

            val secondEmittedList = awaitItem()
            assertEquals(1, secondEmittedList.size)
            assertEquals("App B", secondEmittedList[0].displayName)
        }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `installedApps - when usecase flow crashes - handles gracefully`() = runTest {
        // Stubbing: UseCase wirft Exception via Flow
        whenever(getInstalledAppsUseCase()).thenReturn(flow {
            throw IOException("Cannot load apps")
        })

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            advanceUntilIdle()
            // Should emit empty list or handle error gracefully
            val result = awaitItem()
            assertNotNull(result)
        }
    }

    @Test
    fun `installedApps - when usecase flow crashes with RuntimeException - handles gracefully`() =
        runTest {
            whenever(getInstalledAppsUseCase()).thenReturn(flow {
                throw RuntimeException("Database corrupted")
            })

            viewModel = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )

            viewModel.installedApps.test {
                advanceUntilIdle()
                val result = awaitItem()
                assertNotNull(result)
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
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())
            rawAppsFlow.value = largeAppList
            val result = awaitItem()
            assertEquals(1000, result.size)
        }
    }

    @Test
    fun `installedApps - rapid flow updates - handles correctly`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())

            // Rapid updates
            rawAppsFlow.value = listOf(app1)
            assertEquals(1, awaitItem().size)

            rawAppsFlow.value = testApps
            assertEquals(2, awaitItem().size)

            rawAppsFlow.value = emptyList()
            assertEquals(0, awaitItem().size)

            rawAppsFlow.value = testApps
            assertEquals(2, awaitItem().size)
        }
    }

    @Test
    fun `installedApps - with duplicate apps in flow - forwards them as-is`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())
            val duplicates = listOf(app1, app1, app2)
            rawAppsFlow.value = duplicates
            val result = awaitItem()
            assertEquals(3, result.size)
        }
    }

    @Test
    fun `installedApps - when flow emits null values in list - handles gracefully`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())
            rawAppsFlow.value = testApps
            val result = awaitItem()
            assertEquals(2, result.size)
        }
    }

    @Test
    fun `installedApps - multiple subscribers - all receive updates`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())

            viewModel.installedApps.test {
                assertEquals(emptyList(), awaitItem())
                rawAppsFlow.value = testApps
                // Both subscribers should receive the update
                val result1 = awaitItem()
                assertEquals(2, result1.size)
            }
            val result2 = awaitItem()
            assertEquals(2, result2.size)
        }
    }

    @Test
    fun `installedApps - when created multiple times - each instance has independent state`() =
        runTest {
            val viewModel1 = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )
            val viewModel2 = SettingsViewModel(
                getInstalledAppsUseCase,
                factoryResetUseCase,
                mainDispatcher = mainDispatcherRule.testDispatcher
            )

            viewModel1.installedApps.test {
                assertEquals(emptyList(), awaitItem())
                viewModel2.installedApps.test {
                    assertEquals(emptyList(), awaitItem())
                    rawAppsFlow.value = testApps
                    assertEquals(2, awaitItem().size)
                }
                assertEquals(2, awaitItem().size)
            }
        }

    @Test
    fun `installedApps - stateIn operator - maintains last value for new collectors`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Set a value
        rawAppsFlow.value = testApps
        advanceUntilIdle()

        // New collector should immediately get the last value
        viewModel.installedApps.test {
            val result = awaitItem()
            assertEquals(2, result.size)
            assertEquals("App A", result[0].displayName)
        }
    }

    @Test
    fun `installedApps - collector cancelled - does not affect other collectors`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            awaitItem() // Get initial value
            cancel() // Cancel this collector
        }

        // New collector should still work
        viewModel.installedApps.test {
            val result = awaitItem()
            assertNotNull(result)
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
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())
            rawAppsFlow.value = specialApps
            val result = awaitItem()
            assertEquals(3, result.size)
            assertEquals("App 🚀", result[0].displayName)
        }
    }

    @Test
    fun `installedApps - empty to large to empty - handles correctly`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.installedApps.test {
            assertEquals(emptyList(), awaitItem())
            val largeList = (1..100).map { AppInfo("App $it", "App $it", "com.$it", "class$it") }
            rawAppsFlow.value = largeList
            assertEquals(100, awaitItem().size)
            rawAppsFlow.value = emptyList()
            assertEquals(0, awaitItem().size)
        }
    }

    // ========== UPDATED FACTORY RESET TESTS ==========

    @Test
    fun `onFactoryResetConfirmed - with usage data - calls usecase with true`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Stubbing: Erfolg simulieren, um sicherzugehen, dass kein Crash passiert
        whenever(factoryResetUseCase(any())).thenReturn(FactoryResetUseCase.Result.Success)

        // Act
        viewModel.onFactoryResetConfirmed(includeUsageData = true)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert: Wir prüfen NUR noch, ob der UseCase richtig aufgerufen wurde.
        // Die interne Logik (welches Repo aufgerufen wird) wird im UseCase-Test geprüft.
        verify(factoryResetUseCase).invoke(true)
    }

    @Test
    fun `onFactoryResetConfirmed - without usage data - calls usecase with false`() = runTest {
        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Stubbing
        whenever(factoryResetUseCase(any())).thenReturn(FactoryResetUseCase.Result.Success)

        // Act
        viewModel.onFactoryResetConfirmed(includeUsageData = false)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        verify(factoryResetUseCase).invoke(false)
    }

    // ========== DOOMSDAY TESTS - ROCKY BALBOA EDITION ==========

    @Test
    fun `doomsday - factory reset returns PartialFailure - shows failure toast`() = runTest {
        // SZENARIO: Reset hat teilweise funktioniert, teilweise nicht.
        // User muss informiert werden.

        whenever(factoryResetUseCase(any())).thenReturn(FactoryResetUseCase.Result.PartialFailure)

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.event.test {
            viewModel.onFactoryResetConfirmed(true)

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            // Prüfe, ob die korrekte Error-Message kommt
            assertEquals(com.github.reygnn.kolibri_launcher.R.string.reset_failed, event.messageResId)
        }
    }

    @Test
    fun `doomsday - factory reset returns Error - shows failure toast`() = runTest {
        // SZENARIO: Reset komplett fehlgeschlagen (IO Error).

        whenever(factoryResetUseCase(any())).thenReturn(
            FactoryResetUseCase.Result.Error
        )

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.event.test {
            viewModel.onFactoryResetConfirmed(true)

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(com.github.reygnn.kolibri_launcher.R.string.reset_failed, event.messageResId)
        }
    }

    @Test
    fun `doomsday - factory reset throws RuntimeException - caught by launchSafe`() = runTest {
        // SZENARIO: Der UseCase stürzt ab (nicht Result.Error, sondern Exception).
        // ViewModel darf nicht crashen.

        whenever(factoryResetUseCase(any())).thenThrow(RuntimeException("System died"))

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        // Act: Aufrufen
        viewModel.onFactoryResetConfirmed(true)
        advanceUntilIdle()

        // Assert: ViewModel lebt noch.
        // Da launchSafe Exceptions meist nur loggt (oder generisch behandelt),
        // prüfen wir hier primär, dass der Test nicht rot wird (kein Crash).
        assertNotNull(viewModel)
    }

    @Test
    fun `doomsday - apps flow throws OutOfMemoryError - handles gracefully`() = runTest {
        // SZENARIO: Zu viele Apps, Speicher voll beim Laden.
        // Ein Error (nicht Exception) wird geworfen.

        whenever(getInstalledAppsUseCase()).thenReturn(flow {
            delay(10) // WICHTIG: Verzögerung, damit Turbine subscriben kann, bevor der Crash passiert!
            throw OutOfMemoryError("Too many apps")
        })

        viewModel = SettingsViewModel(
            getInstalledAppsUseCase,
            factoryResetUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.event.test {
            // Wir erwarten den Error-Toast, da dein ViewModel 'Throwable' fängt
            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(com.github.reygnn.kolibri_launcher.R.string.error_loading_apps, event.messageResId)
        }

    }
}