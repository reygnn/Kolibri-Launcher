package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RemoveCustomNameUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCustomNameUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.ReactiveFakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.customnames.CustomNamesViewModel
import com.google.common.truth.Truth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class CustomNamesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeCustomNamesRepository: FakeCustomNamesRepository
    private lateinit var fakeInstalledAppsRepository: ReactiveFakeInstalledAppsRepository
    private lateinit var viewModel: CustomNamesViewModel

    // UseCases
    private lateinit var setCustomNameUseCase: SetCustomNameUseCase
    private lateinit var removeCustomNameUseCase: RemoveCustomNameUseCase
    private lateinit var getInstalledAppsUseCase: GetInstalledAppsUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeCustomNamesRepository = FakeCustomNamesRepository()
        fakeInstalledAppsRepository = ReactiveFakeInstalledAppsRepository(fakeCustomNamesRepository)

        // Initialize UseCases with Fakes
        setCustomNameUseCase = SetCustomNameUseCase(fakeCustomNamesRepository)
        removeCustomNameUseCase = RemoveCustomNameUseCase(fakeCustomNamesRepository)
        getInstalledAppsUseCase = GetInstalledAppsUseCase(fakeInstalledAppsRepository)

        viewModel = CustomNamesViewModel(
            setCustomNameUseCase,
            removeCustomNameUseCase,
            getInstalledAppsUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `init - collects pre-processed app list from flow`() = runTest {
        fakeCustomNamesRepository.setCustomNameForPackage("com.android.camera", "My Camera")

        viewModel.uiState.test {
            awaitItem() // Initial state

            fakeInstalledAppsRepository.triggerAppsUpdate()

            val state = awaitItem()
            Truth.assertThat(state.displayedApps).hasSize(3)
            Truth.assertThat(state.displayedApps.map { it.displayName }).containsExactly(
                "Calculator", "Clock", "My Camera"
            ).inOrder()

            Truth.assertThat(state.appsWithCustomNames).hasSize(1)
            Truth.assertThat(state.appsWithCustomNames.first().displayName).isEqualTo("My Camera")
        }
    }

    @Test
    fun `setCustomName - calls repository and triggers update`() = runTest {
        fakeCustomNamesRepository.onUpdateTrigger = {
            fakeInstalledAppsRepository.triggerAppsUpdate()
        }

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()

            val loadedState = awaitItem()
            Truth.assertThat(loadedState.displayedApps).hasSize(3)
            Truth.assertThat(loadedState.appsWithCustomNames).isEmpty()

            viewModel.setCustomName("com.android.clock", "World Clock")

            val finalState = awaitItem()

            Truth.assertThat(fakeCustomNamesRepository.hasCustomNameForPackage("com.android.clock"))
                .isTrue()
            Truth.assertThat(finalState.appsWithCustomNames).hasSize(1)
            Truth.assertThat(finalState.appsWithCustomNames.first().displayName)
                .isEqualTo("World Clock")
            Truth.assertThat(finalState.displayedApps.map { it.displayName }).containsExactly(
                "Calculator", "Camera", "World Clock"
            ).inOrder()
        }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `init - when installedApps flow throws IOException - handles gracefully`() = runTest {
        val crashingRepository = object : InstalledAppsRepository {
            override fun getInstalledApps() = flow<List<AppInfo>> {
                throw IOException("Cannot load apps")
            }

            override suspend fun triggerAppsUpdate() {}
            override suspend fun purgeRepository() {}
        }

        val crashingUseCase = GetInstalledAppsUseCase(crashingRepository)

        val vm = CustomNamesViewModel(
            setCustomNameUseCase,
            removeCustomNameUseCase,
            crashingUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        vm.uiState.test {
            val state = awaitItem()

            assertTrue(state.displayedApps.isEmpty())
            assertTrue(state.appsWithCustomNames.isEmpty())
        }
    }

    @Test
    fun `init - when installedApps flow throws RuntimeException - handles gracefully`() = runTest {
        val crashingRepository = object : InstalledAppsRepository {
            override fun getInstalledApps() = flow<List<AppInfo>> {
                throw RuntimeException("Database corrupted")
            }

            override suspend fun triggerAppsUpdate() {}
            override suspend fun purgeRepository() {}
        }

        val crashingUseCase = GetInstalledAppsUseCase(crashingRepository)

        val vm = CustomNamesViewModel(
            setCustomNameUseCase,
            removeCustomNameUseCase,
            crashingUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        vm.uiState.test {
            val state = awaitItem()
            assertNotNull(state)
        }
    }

    @Test
    fun `setCustomName - when repository throws IOException - does not crash`() = runTest {
        fakeCustomNamesRepository.shouldFailOnSet = true

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()
            awaitItem()

            viewModel.setCustomName("com.android.clock", "New Name")

            expectNoEvents()
        }
    }

    @Test
    fun `setCustomName - with empty package name - does not crash`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            viewModel.setCustomName("", "New Name")

            expectNoEvents()
        }
    }

    @Test
    fun `setCustomName - with blank package name - does not crash`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            viewModel.setCustomName("  ", "Another Name")

            expectNoEvents()
        }
    }

    @Test
    fun `setCustomName - with empty custom name - removes custom name`() = runTest {
        fakeCustomNamesRepository.onUpdateTrigger = {
            fakeInstalledAppsRepository.triggerAppsUpdate()
        }

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()
            awaitItem()

            // Set a name first
            viewModel.setCustomName("com.android.clock", "Custom Name")
            awaitItem()

            // Remove it with empty string
            viewModel.setCustomName("com.android.clock", "")

            val state = awaitItem()
            Truth.assertThat(state.appsWithCustomNames).isEmpty()
        }
    }

    @Test
    fun `setCustomName - called multiple times rapidly - handles correctly`() = runTest {
        fakeCustomNamesRepository.onUpdateTrigger = {
            fakeInstalledAppsRepository.triggerAppsUpdate()
        }

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()
            awaitItem()

            viewModel.setCustomName("com.android.clock", "Name1")
            awaitItem()

            viewModel.setCustomName("com.android.clock", "Name2")
            awaitItem()

            viewModel.setCustomName("com.android.clock", "Name3")
            val finalState = awaitItem()

            Truth.assertThat(finalState.appsWithCustomNames.first().displayName).isEqualTo("Name3")
        }
    }

    @Test
    fun `init - with empty app list - creates empty state`() = runTest {
        val emptyRepository = object : InstalledAppsRepository {
            override fun getInstalledApps() = flow { emit(emptyList<AppInfo>()) }
            override suspend fun triggerAppsUpdate() {}
            override suspend fun purgeRepository() {}
        }

        val emptyUseCase = GetInstalledAppsUseCase(emptyRepository)

        val vm = CustomNamesViewModel(
            setCustomNameUseCase,
            removeCustomNameUseCase,
            emptyUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.displayedApps.isEmpty())
        }
    }

    @Test
    fun `setCustomName - with very long name - handles correctly`() = runTest {
        fakeCustomNamesRepository.onUpdateTrigger = {
            fakeInstalledAppsRepository.triggerAppsUpdate()
        }

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()
            awaitItem()

            val longName = "A".repeat(500)
            viewModel.setCustomName("com.android.clock", longName)

            val state = awaitItem()
            Truth.assertThat(state.appsWithCustomNames.first().displayName).isEqualTo(longName)
        }
    }

    // ========== NEW TESTS FOR BATCH OPERATIONS (Repo Only) ==========

    @Test
    fun `getAllCustomNames - returns empty map when no names set`() = runTest {
        val result = fakeCustomNamesRepository.getAllCustomNames()

        Truth.assertThat(result).isEmpty()
    }

    @Test
    fun `getAllCustomNames - returns all custom names`() = runTest {
        fakeCustomNamesRepository.setCustomNameForPackage("com.android.clock", "My Clock")
        fakeCustomNamesRepository.setCustomNameForPackage("com.android.camera", "My Camera")

        val result = fakeCustomNamesRepository.getAllCustomNames()

        Truth.assertThat(result).hasSize(2)
        Truth.assertThat(result).containsEntry("com.android.clock", "My Clock")
        Truth.assertThat(result).containsEntry("com.android.camera", "My Camera")
    }

    @Test
    fun `setCustomNamesInBatch - sets multiple names at once`() = runTest {
        val names = mapOf(
            "com.android.clock" to "World Clock",
            "com.android.camera" to "Pro Camera",
            "com.android.calculator" to "Math Tool"
        )

        val success = fakeCustomNamesRepository.setCustomNamesInBatch(names)

        Truth.assertThat(success).isTrue()
        Truth.assertThat(fakeCustomNamesRepository.hasCustomNameForPackage("com.android.clock"))
            .isTrue()
        Truth.assertThat(fakeCustomNamesRepository.hasCustomNameForPackage("com.android.camera"))
            .isTrue()
        Truth.assertThat(fakeCustomNamesRepository.hasCustomNameForPackage("com.android.calculator"))
            .isTrue()
    }

    @Test
    fun `setCustomNamesInBatch - triggers update only once`() = runTest {
        var triggerCount = 0
        fakeCustomNamesRepository.onUpdateTrigger = {
            triggerCount++
        }

        val names = mapOf(
            "com.android.clock" to "Name1",
            "com.android.camera" to "Name2",
            "com.android.calculator" to "Name3"
        )

        fakeCustomNamesRepository.setCustomNamesInBatch(names)

        // Should trigger only once, not 3 times
        Truth.assertThat(triggerCount).isEqualTo(1)
    }

    @Test
    fun `setCustomNamesInBatch - with empty map - returns true and triggers once`() = runTest {
        var triggerCount = 0
        fakeCustomNamesRepository.onUpdateTrigger = {
            triggerCount++
        }

        val success = fakeCustomNamesRepository.setCustomNamesInBatch(emptyMap())

        Truth.assertThat(success).isTrue()
        Truth.assertThat(triggerCount).isEqualTo(0) // Empty batch should not trigger
    }

    @Test
    fun `setCustomNamesInBatch - overwrites existing names`() = runTest {
        fakeCustomNamesRepository.setCustomNameForPackage("com.android.clock", "Old Name")

        val names = mapOf("com.android.clock" to "New Name")
        fakeCustomNamesRepository.setCustomNamesInBatch(names)

        val displayName =
            fakeCustomNamesRepository.getDisplayNameForPackage("com.android.clock", "Clock")
        Truth.assertThat(displayName).isEqualTo("New Name")
    }

    @Test
    fun `setCustomNamesInBatch - when fails - returns false`() = runTest {
        fakeCustomNamesRepository.shouldFailOnBatch = true

        val names = mapOf("com.android.clock" to "Name")
        val success = fakeCustomNamesRepository.setCustomNamesInBatch(names)

        Truth.assertThat(success).isFalse()
    }

    @Test
    fun `getAllCustomNames - after batch set - returns all names`() = runTest {
        val names = mapOf(
            "com.package1" to "App 1",
            "com.package2" to "App 2",
            "com.package3" to "App 3"
        )

        fakeCustomNamesRepository.setCustomNamesInBatch(names)

        val result = fakeCustomNamesRepository.getAllCustomNames()
        Truth.assertThat(result).isEqualTo(names)
    }
}