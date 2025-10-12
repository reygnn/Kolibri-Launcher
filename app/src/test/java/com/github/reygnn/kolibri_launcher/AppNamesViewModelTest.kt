package com.github.reygnn.kolibri_launcher

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class AppNamesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeAppNamesRepository: FakeAppNamesRepository
    private lateinit var fakeInstalledAppsRepository: FakeInstalledAppsRepository
    private lateinit var viewModel: AppNamesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeAppNamesRepository = FakeAppNamesRepository()
        fakeInstalledAppsRepository = FakeInstalledAppsRepository(fakeAppNamesRepository)
        viewModel = AppNamesViewModel(fakeAppNamesRepository, fakeInstalledAppsRepository)
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

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `init - when installedApps flow throws IOException - handles gracefully`() = runTest {
        val crashingRepository = object : InstalledAppsRepository {
            override fun getInstalledApps() = flow<List<AppInfo>> {
                throw IOException("Cannot load apps")
            }
            override fun purgeRepository() {}
        }

        val vm = AppNamesViewModel(fakeAppNamesRepository, crashingRepository)

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
            override fun purgeRepository() {}
        }

        val vm = AppNamesViewModel(fakeAppNamesRepository, crashingRepository)

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
            override fun purgeRepository() {}
        }

        val vm = AppNamesViewModel(fakeAppNamesRepository, emptyRepository)

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
}

// ========== FAKE REPOSITORIES ==========

class FakeAppNamesRepository : AppNamesRepository {
    private val customNames = mutableMapOf<String, String>()
    var onUpdateTrigger: (suspend () -> Unit)? = null
    var shouldFailOnSet = false

    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
        return customNames.getOrDefault(packageName, originalName)
    }

    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        if (shouldFailOnSet) {
            throw IOException("Simulated failure")
        }
        customNames[packageName] = customName
        triggerCustomNameUpdate()
        return true
    }

    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        val success = customNames.remove(packageName) != null
        if (success) {
            triggerCustomNameUpdate()
        }
        return success
    }

    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return customNames.containsKey(packageName)
    }

    override suspend fun triggerCustomNameUpdate() {
        onUpdateTrigger?.invoke()
    }

    override fun purgeRepository() {
        customNames.clear()
        onUpdateTrigger = null
    }
}

class FakeInstalledAppsRepository(
    private val appNamesRepository: AppNamesRepository
) : InstalledAppsRepository {

    private val rawApps = listOf(
        AppInfo("Clock", "Clock", "com.android.clock", "com.android.clock.Clock", true),
        AppInfo("Camera", "Camera", "com.android.camera", "com.android.camera.Camera", true),
        AppInfo("Calculator", "Calculator", "com.android.calculator", "com.android.calculator.Calculator", true)
    )
    private val appFlow = MutableStateFlow<List<AppInfo>>(emptyList())

    override fun getInstalledApps(): Flow<List<AppInfo>> {
        return appFlow
    }

    suspend fun triggerAppsUpdate() {
        val processedList = rawApps.map { app ->
            val displayName = appNamesRepository.getDisplayNameForPackage(app.packageName, app.originalName)
            app.copy(displayName = displayName)
        }.sortedBy { it.displayName.lowercase() }

        appFlow.value = processedList
    }

    override fun purgeRepository() {
        appFlow.value = emptyList()
    }
}