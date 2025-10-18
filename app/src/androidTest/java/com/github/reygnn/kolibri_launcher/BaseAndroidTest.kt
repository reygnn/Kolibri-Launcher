package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import androidx.annotation.StyleRes
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltAndroidTest
abstract class BaseAndroidTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    open val testCoroutineRule = TestCoroutineRule(TestCoroutineRule.Mode.FAST)

    @get:Rule(order = 2)
    val disableAnimationsRule = DisableAnimationsRule()

    // Injiziere die einzelnen Repositories direkt
    @Inject
    lateinit var favoritesRepository: FavoritesRepository
    @Inject
    lateinit var appVisibilityRepository: AppVisibilityRepository
    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var appUsageRepository: AppUsageRepository
    @Inject
    lateinit var favoritesOrderRepository: FavoritesOrderRepository
    @Inject
    lateinit var installedAppsRepository: InstalledAppsRepository
    @Inject
    lateinit var appNamesRepository: AppNamesRepository
    @Inject
    lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @Inject
    lateinit var getFavoriteAppsUseCase: GetFavoriteAppsUseCaseRepository
    @Inject
    lateinit var getDrawerAppsUseCase: GetDrawerAppsUseCaseRepository
    @Inject
    lateinit var screenLockRepository: ScreenLockRepository
    @Inject
    lateinit var getOnboardingAppsUseCase: GetOnboardingAppsUseCaseRepository
    @Inject
    lateinit var appUpdateSignal: AppUpdateSignal

    // Tracking von ActivityScenarios für proper Cleanup
    protected var currentScenario: ActivityScenario<*>? = null

    /**
     * @file BaseAndroidTest.kt
     * @description This abstract class serves as the foundation for all instrumented Android tests in the project.
     * It is responsible for setting up the core testing infrastructure, including Hilt for dependency
     * injection, custom coroutine control, and standard test lifecycle management.
     *
     * ARCHITECTURAL OVERVIEW:
     * This class establishes a consistent and reliable testing environment. Its primary responsibilities are:
     * 1.  **Hilt Integration**: Manages the Hilt component lifecycle for each test, allowing for the injection
     *     of test doubles (fakes) of repositories and use cases.
     * 2.  **Coroutine Control**: Integrates `TestCoroutineRule` to gain explicit control over coroutine
     *     execution. This is the cornerstone of writing deterministic, race-free asynchronous tests.
     * 3.  **Test Isolation**: Ensures that each test runs in a clean state by purging all repository data
     *     in the `baseSetup` method before the test body executes.
     * 4.  **Lifecycle Management**: Provides helpers like `launchAndTrackFragment` to properly manage and
     *     clean up `ActivityScenario` instances, preventing memory leaks between tests.
     *
     * THE CRITICAL SETUP SEQUENCE IN `baseSetup()`:
     * The order of operations within the `@Before` method is non-negotiable and critical for the entire
     * testing framework to function correctly.
     *
     *   a. `TestDispatcherModule.setTestDispatcher(...)`:
     *      This is the first and most crucial step. It takes the `TestDispatcher` instance created by `TestCoroutineRule`
     *      and passes it to the static `TestDispatcherModule`. This "primes" the Hilt module *before* Hilt
     *      starts creating dependencies.
     *
     *   b. `hiltRule.inject()`:
     *      This call triggers Hilt to build the dependency graph for the test. When Hilt encounters a request for a
     *      `CoroutineDispatcher` (e.g., in a ViewModel or Fragment), the `TestDispatcherModule` (which we just primed)
     *      will provide our controllable `TestDispatcher` instead of a real one.
     *
     *   c. `purgeRepository()`:
     *      After dependencies are injected, we clear their state to ensure no data from a previous test can
     *      interfere with the current one.
     *
     * This setup ensures that any class under test which uses coroutines (like `FavoritesSortFragment`)
     * will unknowingly receive a dispatcher that is fully controlled by the test itself, making asynchronous
     * code behave synchronously and predictably.
     */
    @Before
    fun baseSetup() {
        // Test-Mode aktivieren BEVOR Hilt injiziert
        HomeViewModel.isInTestMode = true

        // Dispatcher setzen BEVOR Hilt injiziert
        TestDispatcherModule.setTestDispatcher(testCoroutineRule.testDispatcher)

        //-----------------------------------------------------------------------------

        hiltRule.inject()

        /**
         * IMPORTANT: Do NOT "optimize" this manual purging code!
         *
         * While it may look like code duplication that could be replaced with
         * a loop or reflection, this explicit approach is required for reliable
         * test execution with Hilt dependency injection.
         *
         * WHY THIS WORKS:
         * - Each repository is injected individually by Hilt before this method runs
         * - The explicit casting ensures each repository is fully initialized
         * - Hilt's injection happens at a specific lifecycle point
         *
         * WHAT BREAKS IF YOU "OPTIMIZE" THIS:
         * - Using a list of repositories causes premature access before injection completes
         * - Reflection-based approaches interfere with Hilt's proxy initialization
         * - Tests will crash with "component was not created" errors
         * - The MainActivity launch will fail in instrumented tests
         *
         * This has been tested: the explicit version works, optimized versions fail.
         * Leave this code as-is unless you're willing to debug complex Hilt timing issues.
         */
        (favoritesRepository as? Purgeable)?.purgeRepository()
        (appVisibilityRepository as? Purgeable)?.purgeRepository()
        (settingsRepository as? Purgeable)?.purgeRepository()
        (appUsageRepository as? Purgeable)?.purgeRepository()
        (favoritesOrderRepository as? Purgeable)?.purgeRepository()
        (installedAppsRepository as? Purgeable)?.purgeRepository()
        (appNamesRepository as? Purgeable)?.purgeRepository()
        (installedAppsStateRepository as? Purgeable)?.purgeRepository()
        (getFavoriteAppsUseCase as? Purgeable)?.purgeRepository()
        (getDrawerAppsUseCase as? Purgeable)?.purgeRepository()
        (screenLockRepository as? Purgeable)?.purgeRepository()
        (getOnboardingAppsUseCase as? Purgeable)?.purgeRepository()

        // Reset TestDataSource
        TestDataSource.clearCustomNames()
    }

    // Cleanup nach jedem Test
    @After
    fun baseTearDown() {
        try {
            // ActivityScenario schließen um Memory Leaks zu vermeiden
            currentScenario?.close()
            currentScenario = null
        } catch (e: Exception) {
            // Ignore cleanup errors - Test hat bereits Status
        }

        HomeViewModel.isInTestMode = false   // Test-Mode zurücksetzen
    }

    // Helper-Methode die automatisch das Scenario tracked
    protected inline fun <reified T : Fragment> launchAndTrackFragment(
        fragmentArgs: Bundle? = null,
        @StyleRes themeResId: Int = R.style.AppTheme,
        noinline action: Fragment.() -> Unit = {}
    ): ActivityScenario<HiltTestActivity> {
        // Schließe vorheriges Scenario falls vorhanden
        currentScenario?.close()

        // Starte neues Fragment und tracke das Scenario
        val scenario = launchFragmentInHiltContainer<T>(fragmentArgs, themeResId, action)
        currentScenario = scenario
        return scenario
    }
}