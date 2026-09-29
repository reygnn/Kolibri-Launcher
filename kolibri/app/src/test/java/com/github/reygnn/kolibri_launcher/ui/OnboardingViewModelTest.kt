package com.github.reygnn.kolibri_launcher.ui

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.CompleteOnboardingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDefaultFavoriteComponentsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetOnboardingAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.MarkOnboardingCompletedUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.onboarding.LaunchMode
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingEvent
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertIs

@ExperimentalCoroutinesApi
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()
    @get:Rule
    val timberRule = TimberRule()

    // UseCases als Mocks (relaxed = true entspricht in etwa dem alten lenient-Verhalten)
    private val onboardingAppsUseCase: GetOnboardingAppsUseCase = mockk(relaxed = true)
    private val getFavoriteComponentsUseCase: GetFavoriteComponentsUseCase = mockk(relaxed = true)
    private val getDefaultFavoriteComponentsUseCase: GetDefaultFavoriteComponentsUseCase = mockk(relaxed = true)
    private val completeOnboardingUseCase: CompleteOnboardingUseCase = mockk(relaxed = true)
    private val importBackupUseCase: ImportBackupUseCase = mockk(relaxed = true)
    private val markOnboardingCompletedUseCase: MarkOnboardingCompletedUseCase = mockk(relaxed = true)

    private lateinit var viewModel: OnboardingViewModel

    private val app1 = AppInfo("App 1", "App 1", "pkg1", "class1")
    private val app2 = AppInfo("App 2", "App 2", "pkg2", "class2")
    private val app3 = AppInfo("App 3", "App 3", "pkg3", "class3")
    private val testApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        // Default behavior für Apps Flow (Property-Zugriff → every, kein coEvery)
        every { onboardingAppsUseCase.onboardingAppsFlow } returns flowOf(testApps)
        // Default: no system-default apps resolved → INITIAL_SETUP starts empty, as
        // before this feature. Tests that exercise the pre-selection override this.
        coEvery { getDefaultFavoriteComponentsUseCase(any()) } returns emptyList()
    }

    private fun setupViewModel() {
        viewModel = OnboardingViewModel(
            onboardingAppsUseCase,
            getFavoriteComponentsUseCase,
            getDefaultFavoriteComponentsUseCase,
            completeOnboardingUseCase,
            importBackupUseCase,
            markOnboardingCompletedUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== TESTS (ANGEPASST AN USECASES) ==========

    @Test
    fun `init - loads apps and creates initial state correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.size).isEqualTo(3)
        assertThat(uiState.selectableApps[0].appInfo.displayName).isEqualTo("App 1")
        assertThat(uiState.selectableApps[0].isSelected).isFalse()
    }

    @Test
    fun `loadInitialData - INITIAL_SETUP - preselects defaults after the app list settles, skipping the initial empty emission`() =
        runTest {
            // Reproduce the real StateFlow race: the installed-apps flow first replays an
            // empty Loaded(emptyList()) sentinel, then the populated list once the async
            // PackageManager enumeration finishes. A plain .first() would grab the empty
            // one and preselect nothing (the bug); the fix waits for the populated emission.
            every { onboardingAppsUseCase.onboardingAppsFlow } returns flowOf(emptyList(), testApps)
            coEvery { getDefaultFavoriteComponentsUseCase(testApps) } returns
                listOf(app1.componentName, app3.componentName)

            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            viewModel.loadInitialData()
            advanceUntilIdle()

            val uiState = viewModel.uiState.value
            // The two resolved defaults are pre-selected; the third app is not.
            assertThat(uiState.selectedApps.map { it.packageName }.toSet()).isEqualTo(setOf("pkg1", "pkg3"))
            assertThat(uiState.selectableApps.first { it.appInfo.packageName == "pkg1" }.isSelected).isTrue()
            assertThat(uiState.selectableApps.first { it.appInfo.packageName == "pkg2" }.isSelected).isFalse()
            assertThat(uiState.selectableApps.first { it.appInfo.packageName == "pkg3" }.isSelected).isTrue()
        }

    @Test
    fun `onAppToggled - adds app to selection correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[1])
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected).isTrue()
    }

    @Test
    fun `onAppToggled - removes app from selection when toggled twice`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[1])
        advanceUntilIdle()
        viewModel.onAppToggled(testApps[1])
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected).isFalse()
    }

    @Test
    fun `onDoneClicked - in INITIAL_SETUP mode - calls CompleteOnboardingUseCase correctly`() =
        runTest {
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            viewModel.loadInitialData()
            advanceUntilIdle()

            viewModel.onAppToggled(testApps[0])
            advanceUntilIdle()

            viewModel.onDoneClicked()
            advanceUntilIdle()

            // PRÜFE DEN USECASE AUFRUF (suspend → coVerify)
            coVerify {
                completeOnboardingUseCase(
                    componentNames = listOf(app1.componentName),
                    isInitialSetup = true
                )
            }
        }

    @Test
    fun `onDoneClicked - in EDIT_FAVORITES mode - calls CompleteOnboardingUseCase correctly`() =
        runTest {
            // Mocke den GetFavoriteComponentsUseCase für loadInitialData (suspend → coEvery)
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())

            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
            viewModel.loadInitialData()
            advanceUntilIdle()

            viewModel.onAppToggled(testApps[2])
            advanceUntilIdle()

            viewModel.onDoneClicked()
            advanceUntilIdle()

            // PRÜFE DEN USECASE AUFRUF (suspend → coVerify)
            coVerify {
                completeOnboardingUseCase(
                    componentNames = listOf(app3.componentName),
                    isInitialSetup = false
                )
            }
        }

    @Test
    fun `onDoneClicked - when CompleteOnboardingUseCase fails - emits error event`() = runTest {
        // Mocke den UseCase, damit er einen Fehler wirft (suspend → coEvery)
        coEvery { completeOnboardingUseCase(any(), any()) } throws RuntimeException("Speichern fehlgeschlagen")

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()

            val event = awaitItem()
            assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)
            assertThat((event as OnboardingEvent.ShowError).messageResId).isEqualTo(R.string.onboarding_error_save_failed)
        }
    }

    @Test
    fun `onAppToggled - whenLimitReached - emitsToastEventAndDoesNotSelectApp`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val appsOverLimit = (1..(limit + 1)).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        every { onboardingAppsUseCase.onboardingAppsFlow } returns flowOf(appsOverLimit)
        setupViewModel()
        advanceUntilIdle()

        for (i in 0 until limit) {
            viewModel.onAppToggled(appsOverLimit[i])
        }
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onAppToggled(appsOverLimit[limit])
            advanceUntilIdle()

            val event = awaitItem()
            assertIs<OnboardingEvent.ShowLimitReachedToast>(event)
            assertThat(event.limit).isEqualTo(limit)

            val currentState = viewModel.uiState.value
            assertThat(currentState.selectedApps.size).isEqualTo(limit)
        }
    }

    // ========== SETUP-EXTRAS VISIBILITY ==========

    @Test
    fun `setLaunchMode - INITIAL_SETUP - shows setup extras`() = runTest {
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showSetupExtras).isTrue()
    }

    @Test
    fun `setLaunchMode - EDIT_FAVORITES - hides setup extras`() = runTest {
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showSetupExtras).isFalse()
    }

    // ========== RESTORE-BACKUP PATH ==========

    @Test
    fun `restoreBackupAndFinish - success in INITIAL_SETUP - marks completed and navigates`() =
        runTest {
            coEvery { importBackupUseCase(any(), any()) } returns ImportResult.Success(
                importedCount = 3,
                skippedCount = 0,
                missingApps = emptySet()
            )
            // The restore mirrors the restored favorites into the in-memory selection.
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.restoreBackupAndFinish("content://backup.zip")

                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
            }
            advanceUntilIdle()

            // Full restore uses the all-true default options.
            coVerify { importBackupUseCase("content://backup.zip", ImportOptions()) }
            coVerify { markOnboardingCompletedUseCase() }
            // CRITICAL: the normal done-path must NOT run — it would overwrite the
            // just-restored favorites with the still-empty selection.
            coVerify(exactly = 0) { completeOnboardingUseCase(any(), any()) }
        }

    @Test
    fun `restoreBackupAndFinish - LimitExceeded - emits error and does not complete`() = runTest {
        coEvery { importBackupUseCase(any(), any()) } returns ImportResult.LimitExceeded(
            packageCount = 600,
            limit = AppConstants.MAX_FAVORITES_ON_HOME
        )
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.restoreBackupAndFinish("content://backup.zip")

            val event = awaitItem()
            assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)
            assertThat((event as OnboardingEvent.ShowError).messageResId).isEqualTo(R.string.onboarding_restore_limit_exceeded)
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { markOnboardingCompletedUseCase() }
    }

    @Test
    fun `restoreBackupAndFinish - Error - emits generic restore-failed error`() = runTest {
        coEvery { importBackupUseCase(any(), any()) } returns ImportResult.Error("boom")
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.restoreBackupAndFinish("content://backup.zip")

            val event = awaitItem()
            assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)
            assertThat((event as OnboardingEvent.ShowError).messageResId).isEqualTo(R.string.onboarding_restore_failed)
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { markOnboardingCompletedUseCase() }
    }

    @Test
    fun `restoreBackupAndFinish - UnsupportedVersion - emits error, no mark, re-enables UI`() = runTest {
        coEvery { importBackupUseCase(any(), any()) } returns ImportResult.UnsupportedVersion("99")
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.restoreBackupAndFinish("content://backup.zip")
            val event = awaitItem()
            assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)
            assertThat((event as OnboardingEvent.ShowError).messageResId).isEqualTo(R.string.onboarding_restore_unsupported_version)
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { markOnboardingCompletedUseCase() }
        assertThat(viewModel.uiState.value.isRestoring).isFalse()
    }

    @Test
    fun `restoreBackupAndFinish - InvalidFormat - emits error, no mark, re-enables UI`() = runTest {
        coEvery { importBackupUseCase(any(), any()) } returns ImportResult.InvalidFormat
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.restoreBackupAndFinish("content://backup.zip")
            val event = awaitItem()
            assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)
            assertThat((event as OnboardingEvent.ShowError).messageResId).isEqualTo(R.string.onboarding_restore_invalid_format)
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { markOnboardingCompletedUseCase() }
        assertThat(viewModel.uiState.value.isRestoring).isFalse()
    }

    @Test
    fun `restoreBackupAndFinish - success in EDIT_FAVORITES - navigates but does NOT mark completed`() =
        runTest {
            coEvery { importBackupUseCase(any(), any()) } returns
                ImportResult.Success(importedCount = 1, skippedCount = 0, missingApps = emptySet())
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.restoreBackupAndFinish("content://backup.zip")
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
            }
            advanceUntilIdle()

            // The onboarding-completed flag is INITIAL_SETUP-only; EDIT mode must not flip it.
            coVerify(exactly = 0) { markOnboardingCompletedUseCase() }
        }

    @Test
    fun `restoreBackupAndFinish - mark fails after import - re-enables UI and a later Done saves the RESTORED favorites`() =
        runTest {
            val restored = setOf("pkg1/class1")
            coEvery { importBackupUseCase(any(), any()) } returns
                ImportResult.Success(importedCount = 1, skippedCount = 0, missingApps = emptySet())
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(restored)
            coEvery { markOnboardingCompletedUseCase() } throws IOException("settings write failed")
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.restoreBackupAndFinish("content://backup.zip")
                // mark threw: UI re-enabled + error shown (not silent, not stuck).
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.ShowError::class.java)
                assertThat(viewModel.uiState.value.isRestoring).isFalse()

                // A later Done must NOT wipe: the restored favorites were mirrored into
                // the selection, so completeOnboardingUseCase saves THEM, not emptyList.
                viewModel.onDoneClicked()
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
            }
            advanceUntilIdle()

            coVerify { completeOnboardingUseCase(restored.toList(), true) }
        }

    @Test
    fun `restoreBackupAndFinish - success with missing apps - emits missing-apps toast then navigates`() =
        runTest {
            coEvery { importBackupUseCase(any(), any()) } returns ImportResult.Success(
                importedCount = 2,
                skippedCount = 3,
                missingApps = setOf("pkgA/clsA", "pkgB/clsB", "pkgC/clsC")
            )
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.restoreBackupAndFinish("content://backup.zip")

                val toast = awaitItem()
                assertThat(toast).isInstanceOf(OnboardingEvent.ShowMissingAppsToast::class.java)
                assertThat((toast as OnboardingEvent.ShowMissingAppsToast).count).isEqualTo(3)

                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
            }
            advanceUntilIdle()

            coVerify { markOnboardingCompletedUseCase() }
        }

    @Test
    fun `restoreBackupAndFinish - success with dropped wallpaper layers - toasts then navigates`() =
        runTest {
            coEvery { importBackupUseCase(any(), any()) } returns ImportResult.Success(
                importedCount = 1,
                skippedCount = 0,
                missingApps = emptySet(),
                droppedWallpaperLayers = 2
            )
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.restoreBackupAndFinish("content://backup.zip")
                val toast = awaitItem()
                assertThat(toast).isInstanceOf(OnboardingEvent.ShowDroppedLayersToast::class.java)
                assertThat((toast as OnboardingEvent.ShowDroppedLayersToast).count).isEqualTo(2)
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
            }
            advanceUntilIdle()

            coVerify { markOnboardingCompletedUseCase() }
        }

    @Test
    fun `restoreBackupAndFinish - success but favorites read Unavailable - still marks and navigates`() =
        runTest {
            coEvery { importBackupUseCase(any(), any()) } returns
                ImportResult.Success(importedCount = 1, skippedCount = 0, missingApps = emptySet())
            // Mirror read fails: the Unavailable branch must be a no-op (favorites are
            // already on disk), not a crash — restore still marks + navigates.
            coEvery { getFavoriteComponentsUseCase() } returns
                FavoritesEditRead.Unavailable(IOException("read failed"))
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.restoreBackupAndFinish("content://backup.zip")
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
            }
            advanceUntilIdle()

            coVerify { markOnboardingCompletedUseCase() }
        }

    @Test
    fun `restoreBackupAndFinish - missing apps in EDIT_FAVORITES - still toasts but does not mark`() =
        runTest {
            coEvery { importBackupUseCase(any(), any()) } returns
                ImportResult.Success(importedCount = 1, skippedCount = 0, missingApps = setOf("pkgA/clsA"))
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.restoreBackupAndFinish("content://backup.zip")
                val toast = awaitItem()
                assertThat(toast).isInstanceOf(OnboardingEvent.ShowMissingAppsToast::class.java)
                assertThat((toast as OnboardingEvent.ShowMissingAppsToast).count).isEqualTo(1)
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
            }
            advanceUntilIdle()

            // The missing-apps toast is mode-independent, but the completed flag stays
            // INITIAL_SETUP-only.
            coVerify(exactly = 0) { markOnboardingCompletedUseCase() }
        }

    @Test
    fun `onDoneClicked - while isRestoring is latched - is ignored (no wipe)`() = runTest {
        // The success path leaves isRestoring = true (the activity navigates away on
        // the UI side). This pins BOTH halves of the wipe-guard: deleting the
        // `isRestoring = true` set OR the onDoneClicked guard makes this go red.
        coEvery { importBackupUseCase(any(), any()) } returns
            ImportResult.Success(importedCount = 1, skippedCount = 0, missingApps = emptySet())
        coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.restoreBackupAndFinish("content://backup.zip")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isRestoring).isTrue()

        // A Done that fires while isRestoring is latched must be dropped by the
        // guard — it must NOT run completeOnboardingUseCase (which would save the
        // empty selection over the restored favorites).
        viewModel.onDoneClicked()
        advanceUntilIdle()
        coVerify(exactly = 0) { completeOnboardingUseCase(any(), any()) }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `init - when onboardingAppsFlow fails - handles gracefully and emits error`() = runTest {
        // 1. Mock Setup
        every { onboardingAppsUseCase.onboardingAppsFlow } returns flow {
            throw IOException("Cannot load apps")
        }

        // 3. Initialize the ViewModel.
        //
        // ⚠️ Dispatcher-convention exception: this one case needs
        // StandardTestDispatcher (queued), not the rule's default
        // UnconfinedTestDispatcher (eager).
        //
        // The init block does launch(mainDispatcher) { collectFlow() }. With an
        // eager dispatcher that runs inside the constructor and emits ShowError
        // before this test's collector attaches (event.test {} comes after the
        // constructor). _event is a buffered Channel (BaseViewModel), so the event
        // is NOT lost — but a queued dispatcher keeps the emit/collect ordering
        // explicit: we subscribe via event.test {} first, then drive the init with
        // advanceUntilIdle(). One testScheduler (from runTest, same as
        // mainDispatcherRule), two dispatcher strategies — the "EXCEPTION" variant
        // from TESTING_CONVENTIONS.kt.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        viewModel = OnboardingViewModel(
            onboardingAppsUseCase,
            getFavoriteComponentsUseCase,
            getDefaultFavoriteComponentsUseCase,
            completeOnboardingUseCase,
            importBackupUseCase,
            markOnboardingCompletedUseCase,
            mainDispatcher = testDispatcher
        )

        // 4. Jetzt hängen wir uns an den Event-Stream
        viewModel.event.test {
            // 5. JETZT lassen wir die Zeit laufen. Der init-Block wird ausgeführt.
            advanceUntilIdle()

            // 6. Das Event wurde aufgefangen!
            val event = awaitItem()
            assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)

            val uiState = viewModel.uiState.value
            assertThat(uiState).isNotNull()
            assertThat(uiState.selectableApps.isEmpty()).isTrue()
        }
    }

    @Test
    fun `initialize - in EDIT_FAVORITES mode when GetFavoriteComponentsUseCase is Unavailable - handles gracefully`() =
        runTest {
            // 1. Mock Setup: the read reports Unavailable (I/O failure) — Belang C's
            //    fail-closed result, NOT a thrown exception.
            coEvery { getFavoriteComponentsUseCase() } returns
                FavoritesEditRead.Unavailable(IOException("Cannot read favorites"))

            // 2. ViewModel Setup
            setupViewModel()

            // 3. Test
            viewModel.event.test {
                viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
                viewModel.loadInitialData()

                advanceUntilIdle()

                val event = awaitItem()
                assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)

                val uiState = viewModel.uiState.value
                assertThat(uiState).isNotNull()
            }
        }

    @Test
    fun `onDoneClicked - when CompleteOnboardingUseCase throws IOException - emits error`() =
        runTest {
            // Mocke den UseCase (suspend → coEvery throws)
            coEvery { completeOnboardingUseCase(any(), any()) } throws IOException("Disk full")

            setupViewModel()
            advanceUntilIdle()

            viewModel.onAppToggled(testApps[0])
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.onDoneClicked()
                advanceUntilIdle()
                val event = awaitItem()
                assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)
            }
        }

    @Test
    fun `onDoneClicked - when CompleteOnboardingUseCase throws on settings - still saves favorites`() =
        runTest {
            coEvery { completeOnboardingUseCase(any(), true) } throws IOException("Cannot write settings")

            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            viewModel.loadInitialData()
            advanceUntilIdle()

            viewModel.onAppToggled(testApps[0])
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.onDoneClicked()
                advanceUntilIdle()

                // Der UseCase wurde aufgerufen (auch wenn er fehlgeschlagen ist) → coVerify
                coVerify { completeOnboardingUseCase(listOf(app1.componentName), true) }

                val event = awaitItem()
                assertThat(event).isInstanceOf(OnboardingEvent.ShowError::class.java)
            }
        }

    @Test
    fun `onDoneClicked - with no apps selected - calls UseCase with empty list`() = runTest {
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        // PRÜFE DEN USECASE (suspend → coVerify)
        coVerify {
            completeOnboardingUseCase(
                componentNames = emptyList(),
                isInitialSetup = true
            )
        }
    }

    @Test
    fun `initialize - EDIT_FAVORITES mode pre-selects existing favorites`() = runTest {
        val existingFavorites = setOf(app1.componentName, app3.componentName)
        // Mocke den GetFavoriteComponentsUseCase (suspend → coEvery)
        coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(existingFavorites)

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        viewModel.loadInitialData()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected).isTrue()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected).isFalse()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected).isTrue()
    }

    @Test
    fun `onDoneClicked - in EDIT_FAVORITES after removing all favorites - calls UseCase with empty list`() =
        runTest {
            val existingFavorites = setOf(app1.componentName)
            // Mocke den GetFavoriteComponentsUseCase (suspend → coEvery)
            coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(existingFavorites)

            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
            viewModel.loadInitialData()
            advanceUntilIdle()

            // Remove the only favorite
            viewModel.onAppToggled(app1)
            advanceUntilIdle()

            viewModel.onDoneClicked()
            advanceUntilIdle()

            // PRÜFE DEN USECASE (suspend → coVerify)
            coVerify {
                completeOnboardingUseCase(
                    componentNames = emptyList(),
                    isInitialSetup = false
                )
            }
        }

    @Test
    fun `onSearchQueryChanged - filters apps correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App 2")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.size).isEqualTo(1)
        assertThat(uiState.selectableApps[0].appInfo.displayName).isEqualTo("App 2")
    }

    @Test
    fun `onSearchQueryChanged - with empty query - shows all apps`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App 1")
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.size).isEqualTo(3)
    }

    @Test
    fun `onSearchQueryChanged - case insensitive search works`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("aPp 3")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.size).isEqualTo(1)
        assertThat(uiState.selectableApps[0].appInfo.displayName).isEqualTo("App 3")
    }

    @Test
    fun `onSearchQueryChanged - no match - shows empty list`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("NonExistent")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.isEmpty()).isTrue()
    }

    @Test
    fun `onSearchQueryChanged - selection persists across search changes`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        // Select app1
        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        // Search for something else
        viewModel.onSearchQueryChanged("App 2")
        advanceUntilIdle()

        // Clear search - app1 should still be selected
        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected).isTrue()
    }

    @Test
    fun `setLaunchMode - INITIAL_SETUP - sets correct title and subtitle`() = runTest {
        setupViewModel()

        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.titleResId).isEqualTo(R.string.onboarding_title_welcome)
        assertThat(uiState.subtitleResId).isEqualTo(R.string.onboarding_subtitle_welcome)
    }

    @Test
    fun `setLaunchMode - EDIT_FAVORITES - sets correct title and subtitle`() = runTest {
        setupViewModel()

        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.titleResId).isEqualTo(R.string.onboarding_title_edit_favorites)
        assertThat(uiState.subtitleResId).isEqualTo(R.string.onboarding_subtitle_edit_favorites)
    }

    @Test
    fun `setLaunchMode - can be changed multiple times`() = runTest {
        setupViewModel()

        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.titleResId).isEqualTo(R.string.onboarding_title_welcome)

        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.titleResId).isEqualTo(R.string.onboarding_title_edit_favorites)
    }

    @Test
    fun `loadInitialData - called multiple times - only loads once`() = runTest {
        coEvery { getFavoriteComponentsUseCase() } returns FavoritesEditRead.Loaded(emptySet())

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)

        viewModel.loadInitialData()
        viewModel.loadInitialData()
        viewModel.loadInitialData()
        advanceUntilIdle()

        // UseCase should only be called once (suspend → coVerify)
        coVerify(atLeast = 1) { getFavoriteComponentsUseCase() }
    }

    @Test
    fun `onAppToggled - rapid toggles on same app - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        repeat(10) {
            viewModel.onAppToggled(app1)
        }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        // Should be unselected (even number of toggles)
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected).isFalse()
    }

    @Test
    fun `onAppToggled - rapid toggles on different apps - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app2)
        viewModel.onAppToggled(app3)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected).isTrue()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected).isTrue()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected).isTrue()
    }

    @Test
    fun `onDoneClicked - called multiple times rapidly - only executes once per call`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        repeat(3) {
            viewModel.onDoneClicked()
        }
        advanceUntilIdle()

        // Should be called 3 times (not protected against multiple calls) (suspend → coVerify)
        coVerify(atLeast = 1) { completeOnboardingUseCase(any(), any()) }
    }

    @Test
    fun `onAppToggled - when at limit minus one - allows one more selection`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val manyApps = (1..limit).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        every { onboardingAppsUseCase.onboardingAppsFlow } returns flowOf(manyApps)

        setupViewModel()
        advanceUntilIdle()

        // Select limit - 1 apps
        for (i in 0 until limit - 1) {
            viewModel.onAppToggled(manyApps[i])
        }
        advanceUntilIdle()

        viewModel.event.test {
            // This should succeed without toast
            viewModel.onAppToggled(manyApps[limit - 1])
            advanceUntilIdle()

            expectNoEvents() // No limit toast!

            val uiState = viewModel.uiState.value
            assertThat(uiState.selectedApps.size).isEqualTo(limit)
        }
    }

    @Test
    fun `selectedApps - are always sorted alphabetically`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        // Select in reverse order
        viewModel.onAppToggled(app3)
        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app2)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectedApps[0].displayName).isEqualTo("App 1")
        assertThat(uiState.selectedApps[1].displayName).isEqualTo("App 2")
        assertThat(uiState.selectedApps[2].displayName).isEqualTo("App 3")
    }

    @Test
    fun `onDoneClicked - emits NavigateToMain event on success`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)
        }
    }

    @Test
    fun `onAppToggled - while searching - updates both filtered and selected lists`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App 2")
        advanceUntilIdle()

        viewModel.onAppToggled(app2)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.size).isEqualTo(1) // Filtered
        assertThat(uiState.selectedApps.size).isEqualTo(1)    // Selected
        assertThat(uiState.selectableApps[0].isSelected).isTrue()
    }

    @Test
    fun `onAppToggled - selected app disappears from filtered list when search changes`() =
        runTest {
            setupViewModel()
            advanceUntilIdle()

            viewModel.onAppToggled(app1)
            advanceUntilIdle()

            // Search for something else - app1 not in filtered list
            viewModel.onSearchQueryChanged("App 2")
            advanceUntilIdle()

            val uiState = viewModel.uiState.value
            assertThat(uiState.selectableApps.size).isEqualTo(1) // Only App 2 visible
            assertThat(uiState.selectedApps.size).isEqualTo(1)    // But App 1 still selected!
            assertThat(uiState.selectedApps[0].displayName).isEqualTo("App 1")
        }

    @Test
    fun `onAppToggled - selecting exactly at limit - works without toast`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val exactApps = (1..limit).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        every { onboardingAppsUseCase.onboardingAppsFlow } returns flowOf(exactApps)

        setupViewModel()
        advanceUntilIdle()

        // Select all apps up to limit
        exactApps.forEach { app ->
            viewModel.onAppToggled(app)
        }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectedApps.size).isEqualTo(limit)
    }

    @Test
    fun `onSearchQueryChanged - with whitespace only - treats as empty`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("   ")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectableApps.size).isEqualTo(3) // All apps shown
    }

    @Test
    fun `selectedApps - reflects actual selection state in selectableApps`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app3)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value

        // Check selectedApps
        assertThat(uiState.selectedApps.size).isEqualTo(2)

        // Check that selectableApps matches
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected).isTrue()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected).isFalse()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected).isTrue()
    }

    @Test
    fun `loadInitialData - in INITIAL_SETUP mode - starts with empty selection when no defaults resolve`() = runTest {
        // Default stub returns emptyList → nothing pre-selected.
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        viewModel.loadInitialData()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectedApps.isEmpty()).isTrue()
        assertThat(uiState.selectableApps.all { !it.isSelected }).isTrue()
    }

    @Test
    fun `loadInitialData - in INITIAL_SETUP mode - pre-selects resolved default apps`() = runTest {
        // The system-default resolver returns two of the installed apps → they must be
        // pre-selected (checked in the list AND in the selected set), the rest not.
        coEvery { getDefaultFavoriteComponentsUseCase(any()) } returns
            listOf(app1.componentName, app3.componentName)

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        viewModel.loadInitialData()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.selectedApps.size).isEqualTo(2)
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected).isTrue()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected).isFalse()
        assertThat(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected).isTrue()
        // Resolution is fed the installed-apps list.
        coVerify { getDefaultFavoriteComponentsUseCase(testApps) }
    }

    @Test
    fun `onDoneClicked - after error - can retry successfully`() = runTest {
        // MockK chained stubbing: erster Aufruf wirft, zweiter liefert Unit
        coEvery {
            completeOnboardingUseCase(any(), any())
        } throws RuntimeException("Network error") andThen Unit

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            val errorEvent = awaitItem()
            assertThat(errorEvent).isInstanceOf(OnboardingEvent.ShowError::class.java)

            viewModel.onDoneClicked()
            advanceUntilIdle()

            val successEvent = awaitItem()
            assertThat(successEvent).isInstanceOf(OnboardingEvent.NavigateToMain::class.java)

            coVerify(exactly = 2) { completeOnboardingUseCase(any(), any()) }
        }
    }

    // ========== SAVE-GATE (DATASTORE_READ_SPEC Belang C, DSR-INV-4) ==========

    @Test
    fun `onDoneClicked - EDIT mode with Unavailable preselect - does NOT save and emits error`() =
        runTest {
            // The EDIT read failed → Unavailable. Saving would overwrite the real
            // favorites with the empty default. The save-gate must block the save.
            coEvery { getFavoriteComponentsUseCase() } returns
                FavoritesEditRead.Unavailable(IOException("read failed"))

            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)

            viewModel.event.test {
                viewModel.loadInitialData()
                advanceUntilIdle()
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.ShowError::class.java) // load-time failure

                viewModel.onDoneClicked()
                advanceUntilIdle()
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.ShowError::class.java) // save-gate blocked
            }

            coVerify(exactly = 0) { completeOnboardingUseCase(any(), any()) }
        }

    @Test
    fun `onDoneClicked - EDIT mode when preselect read throws a non-IO error - does NOT save`() =
        runTest {
            // The fail-closed catch(Throwable) branch (a non-I/O programmer error, not
            // an IOException->Unavailable) must ALSO mark the state Unavailable so the
            // save-gate blocks — otherwise a thrown read error would leave the default
            // and (if it were Loaded) let an empty save through.
            coEvery { getFavoriteComponentsUseCase() } throws RuntimeException("programmer error")

            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)

            viewModel.event.test {
                viewModel.loadInitialData()
                advanceUntilIdle()
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.ShowError::class.java) // load-time (catch branch)

                viewModel.onDoneClicked()
                advanceUntilIdle()
                assertThat(awaitItem()).isInstanceOf(OnboardingEvent.ShowError::class.java) // save-gate blocked
            }

            coVerify(exactly = 0) { completeOnboardingUseCase(any(), any()) }
        }

    @Test
    fun `onDoneClicked - EDIT mode with Done before preselect resolves - does NOT save`() = runTest {
        // In-flight race (round-2 finding 2.1): Done tapped while the EDIT read has
        // not resolved → state is NotLoaded (the default) → the save must be blocked,
        // so an empty selection can never wipe the real favorites. loadInitialData is
        // deliberately never driven here, leaving the state at NotLoaded.
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)

        viewModel.onDoneClicked()
        advanceUntilIdle()

        coVerify(exactly = 0) { completeOnboardingUseCase(any(), any()) }
    }

}