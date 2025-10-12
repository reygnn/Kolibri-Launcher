package com.github.reygnn.kolibri_launcher

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltAndroidTest
abstract class BaseAndroidTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val testCoroutineRule = TestCoroutineRule()

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


    @Before
    fun baseSetup() {
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
    }
}
