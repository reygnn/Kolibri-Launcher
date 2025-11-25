package com.github.reygnn.kolibri_launcher

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.ReactiveFakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.ui.customnames.CustomNamesViewModel
import com.google.common.truth.Truth.assertThat
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
class AppNamesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeAppNamesRepository: FakeAppNamesRepository
    private lateinit var fakeInstalledAppsRepository: ReactiveFakeInstalledAppsRepository
    private lateinit var viewModel: CustomNamesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeAppNamesRepository = FakeAppNamesRepository()
        fakeInstalledAppsRepository = ReactiveFakeInstalledAppsRepository(fakeAppNamesRepository)
        viewModel = CustomNamesViewModel(
            fakeAppNamesRepository,
            fakeInstalledAppsRepository,
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
        fakeAppNamesRepository.setCustomNameForPackage("com.android.camera", "My Camera")

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()

            val state = awaitItem()
            assertThat(state.displayedApps).hasSize(3)
            assertThat(state.displayedApps.map { it.displayName }).containsExactly(
                "Calculator", "Clock", "My Camera"
            ).inOrder()

            assertThat(state.appsWithCustomNames).hasSize(1)
            assertThat(state.appsWithCustomNames.first().displayName).isEqualTo("My Camera")
        }
    }

    @Test
    fun `setCustomName - calls repository and triggers update`() = runTest {
        fakeAppNamesRepository.onUpdateTrigger = {
            fakeInstalledAppsRepository.triggerAppsUpdate()
        }

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()

            val loadedState = awaitItem()
            assertThat(loadedState.displayedApps).hasSize(3)
            assertThat(loadedState.appsWithCustomNames).isEmpty()

            viewModel.setCustomName("com.android.clock", "World Clock")

            val finalState = awaitItem()

            assertThat(fakeAppNamesRepository.hasCustomNameForPackage("com.android.clock")).isTrue()
            assertThat(finalState.appsWithCustomNames).hasSize(1)
            assertThat(finalState.appsWithCustomNames.first().displayName).isEqualTo("World Clock")
            assertThat(finalState.displayedApps.map { it.displayName }).containsExactly(
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

        val vm = CustomNamesViewModel(
            fakeAppNamesRepository,
            crashingRepository,
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

        val vm = CustomNamesViewModel(
            fakeAppNamesRepository,
            crashingRepository,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        vm.uiState.test {
            val state = awaitItem()
            assertNotNull(state)
        }
    }

    @Test
    fun `setCustomName - when repository throws IOException - does not crash`() = runTest {
        fakeAppNamesRepository.shouldFailOnSet = true

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
        fakeAppNamesRepository.onUpdateTrigger = {
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
            assertThat(state.appsWithCustomNames).isEmpty()
        }
    }

    @Test
    fun `setCustomName - called multiple times rapidly - handles correctly`() = runTest {
        fakeAppNamesRepository.onUpdateTrigger = {
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

            assertThat(finalState.appsWithCustomNames.first().displayName).isEqualTo("Name3")
        }
    }

    @Test
    fun `init - with empty app list - creates empty state`() = runTest {
        val emptyRepository = object : InstalledAppsRepository {
            override fun getInstalledApps() = flow { emit(emptyList<AppInfo>()) }
            override suspend fun triggerAppsUpdate() {}
            override suspend fun purgeRepository() {}
        }

        val vm = CustomNamesViewModel(
            fakeAppNamesRepository,
            emptyRepository,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.displayedApps.isEmpty())
        }
    }

    @Test
    fun `setCustomName - with very long name - handles correctly`() = runTest {
        fakeAppNamesRepository.onUpdateTrigger = {
            fakeInstalledAppsRepository.triggerAppsUpdate()
        }

        viewModel.uiState.test {
            awaitItem()

            fakeInstalledAppsRepository.triggerAppsUpdate()
            awaitItem()

            val longName = "A".repeat(500)
            viewModel.setCustomName("com.android.clock", longName)

            val state = awaitItem()
            assertThat(state.appsWithCustomNames.first().displayName).isEqualTo(longName)
        }
    }

    // ========== NEW TESTS FOR BATCH OPERATIONS ==========

    @Test
    fun `getAllCustomNames - returns empty map when no names set`() = runTest {
        val result = fakeAppNamesRepository.getAllCustomNames()

        assertThat(result).isEmpty()
    }

    @Test
    fun `getAllCustomNames - returns all custom names`() = runTest {
        fakeAppNamesRepository.setCustomNameForPackage("com.android.clock", "My Clock")
        fakeAppNamesRepository.setCustomNameForPackage("com.android.camera", "My Camera")

        val result = fakeAppNamesRepository.getAllCustomNames()

        assertThat(result).hasSize(2)
        assertThat(result).containsEntry("com.android.clock", "My Clock")
        assertThat(result).containsEntry("com.android.camera", "My Camera")
    }

    @Test
    fun `setCustomNamesInBatch - sets multiple names at once`() = runTest {
        val names = mapOf(
            "com.android.clock" to "World Clock",
            "com.android.camera" to "Pro Camera",
            "com.android.calculator" to "Math Tool"
        )

        val success = fakeAppNamesRepository.setCustomNamesInBatch(names)

        assertThat(success).isTrue()
        assertThat(fakeAppNamesRepository.hasCustomNameForPackage("com.android.clock")).isTrue()
        assertThat(fakeAppNamesRepository.hasCustomNameForPackage("com.android.camera")).isTrue()
        assertThat(fakeAppNamesRepository.hasCustomNameForPackage("com.android.calculator")).isTrue()
    }

    @Test
    fun `setCustomNamesInBatch - triggers update only once`() = runTest {
        var triggerCount = 0
        fakeAppNamesRepository.onUpdateTrigger = {
            triggerCount++
        }

        val names = mapOf(
            "com.android.clock" to "Name1",
            "com.android.camera" to "Name2",
            "com.android.calculator" to "Name3"
        )

        fakeAppNamesRepository.setCustomNamesInBatch(names)

        // Should trigger only once, not 3 times
        assertThat(triggerCount).isEqualTo(1)
    }

    @Test
    fun `setCustomNamesInBatch - with empty map - returns true and triggers once`() = runTest {
        var triggerCount = 0
        fakeAppNamesRepository.onUpdateTrigger = {
            triggerCount++
        }

        val success = fakeAppNamesRepository.setCustomNamesInBatch(emptyMap())

        assertThat(success).isTrue()
        assertThat(triggerCount).isEqualTo(0) // Empty batch should not trigger
    }

    @Test
    fun `setCustomNamesInBatch - overwrites existing names`() = runTest {
        fakeAppNamesRepository.setCustomNameForPackage("com.android.clock", "Old Name")

        val names = mapOf("com.android.clock" to "New Name")
        fakeAppNamesRepository.setCustomNamesInBatch(names)

        val displayName = fakeAppNamesRepository.getDisplayNameForPackage("com.android.clock", "Clock")
        assertThat(displayName).isEqualTo("New Name")
    }

    @Test
    fun `setCustomNamesInBatch - when fails - returns false`() = runTest {
        fakeAppNamesRepository.shouldFailOnBatch = true

        val names = mapOf("com.android.clock" to "Name")
        val success = fakeAppNamesRepository.setCustomNamesInBatch(names)

        assertThat(success).isFalse()
    }

    @Test
    fun `getAllCustomNames - after batch set - returns all names`() = runTest {
        val names = mapOf(
            "com.package1" to "App 1",
            "com.package2" to "App 2",
            "com.package3" to "App 3"
        )

        fakeAppNamesRepository.setCustomNamesInBatch(names)

        val result = fakeAppNamesRepository.getAllCustomNames()
        assertThat(result).isEqualTo(names)
    }
}