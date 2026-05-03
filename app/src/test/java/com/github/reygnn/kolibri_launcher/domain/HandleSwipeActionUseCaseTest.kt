package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandleSwipeActionUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var swipeActionsRepository: FakeSwipeActionsRepository
    private lateinit var installedAppsStateRepository: FakeInstalledAppsStateRepository
    private lateinit var appUsageRepository: FakeAppUsageRepository
    private lateinit var installedAppsRepository: FakeInstalledAppsRepository
    private lateinit var useCase: HandleSwipeActionUseCase

    private val testApp = AppInfo("TestApp", "TestApp", "com.test", "com.test.Main")
    private val testApp2 = AppInfo("TestApp2", "TestApp2", "com.test2", "com.test2.Main")

    @Before
    fun setup() {
        swipeActionsRepository = FakeSwipeActionsRepository()
        installedAppsStateRepository = FakeInstalledAppsStateRepository()
        appUsageRepository = FakeAppUsageRepository()
        installedAppsRepository = FakeInstalledAppsRepository()

        val recordAppLaunchUseCase = RecordAppLaunchUseCase(appUsageRepository)
        val refreshAppsUseCase = RefreshAppsUseCase(installedAppsRepository)

        useCase = HandleSwipeActionUseCase(
            swipeActionsRepository,
            installedAppsStateRepository,
            recordAppLaunchUseCase,
            refreshAppsUseCase
        )
    }

    // =========================================================================
    // Erfolgsfall - LEFT
    // =========================================================================

    @Test
    fun `invoke LEFT returns LaunchApp when app assigned and exists`() = runTest {
        // Arrange
        installedAppsStateRepository.updateApps(listOf(testApp))
        swipeActionsRepository.swipeLeftApp = testApp.componentName

        // Act
        val result = useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Assert
        assertThat(result).isInstanceOf(HandleSwipeActionUseCase.Result.LaunchApp::class.java)
        assertThat((result as HandleSwipeActionUseCase.Result.LaunchApp).app).isEqualTo(testApp)
    }

    @Test
    fun `invoke LEFT records app launch`() = runTest {
        // Arrange
        installedAppsStateRepository.updateApps(listOf(testApp))
        swipeActionsRepository.swipeLeftApp = testApp.componentName

        // Act
        useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Assert
        assertThat(appUsageRepository.launchedPackages).contains(testApp.packageName)
    }

    @Test
    fun `invoke LEFT triggers apps refresh`() = runTest {
        // Arrange
        installedAppsStateRepository.updateApps(listOf(testApp))
        swipeActionsRepository.swipeLeftApp = testApp.componentName

        // Act
        useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Assert
        assertThat(installedAppsRepository.triggerUpdateCallCount).isEqualTo(1)
    }

    // =========================================================================
    // Erfolgsfall - RIGHT
    // =========================================================================

    @Test
    fun `invoke RIGHT returns LaunchApp when app assigned and exists`() = runTest {
        // Arrange
        installedAppsStateRepository.updateApps(listOf(testApp2))
        swipeActionsRepository.swipeRightApp = testApp2.componentName

        // Act
        val result = useCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)

        // Assert
        assertThat(result).isInstanceOf(HandleSwipeActionUseCase.Result.LaunchApp::class.java)
        assertThat((result as HandleSwipeActionUseCase.Result.LaunchApp).app).isEqualTo(testApp2)
    }

    // =========================================================================
    // NoAction - Keine App zugewiesen
    // =========================================================================

    @Test
    fun `invoke LEFT returns NoAction when no app assigned`() = runTest {
        // Arrange
        swipeActionsRepository.swipeLeftApp = null

        // Act
        val result = useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Assert
        assertThat(result).isEqualTo(HandleSwipeActionUseCase.Result.NoAction)
    }

    @Test
    fun `invoke RIGHT returns NoAction when no app assigned`() = runTest {
        // Arrange
        swipeActionsRepository.swipeRightApp = null

        // Act
        val result = useCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)

        // Assert
        assertThat(result).isEqualTo(HandleSwipeActionUseCase.Result.NoAction)
    }

    @Test
    fun `invoke does not record launch when no app assigned`() = runTest {
        // Arrange
        swipeActionsRepository.swipeLeftApp = null

        // Act
        useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Assert
        assertThat(appUsageRepository.launchedPackages).isEmpty()
    }

    // =========================================================================
    // NoAction - App nicht mehr installiert
    // =========================================================================

    @Test
    fun `invoke returns NoAction when assigned app not installed`() = runTest {
        // Arrange: App zugewiesen, aber nicht in installedApps
        installedAppsStateRepository.updateApps(emptyList())
        swipeActionsRepository.swipeLeftApp = "com.uninstalled/com.uninstalled.Main"

        // Act
        val result = useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Assert
        assertThat(result).isEqualTo(HandleSwipeActionUseCase.Result.NoAction)
    }

    @Test
    fun `invoke clears LEFT swipe action when app not installed`() = runTest {
        // Arrange
        installedAppsStateRepository.updateApps(emptyList())
        swipeActionsRepository.swipeLeftApp = "com.uninstalled/com.uninstalled.Main"

        // Act
        useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Assert
        assertThat(swipeActionsRepository.swipeLeftApp).isNull()
    }

    @Test
    fun `invoke clears RIGHT swipe action when app not installed`() = runTest {
        // Arrange
        installedAppsStateRepository.updateApps(emptyList())
        swipeActionsRepository.swipeRightApp = "com.uninstalled/com.uninstalled.Main"

        // Act
        useCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)

        // Assert
        assertThat(swipeActionsRepository.swipeRightApp).isNull()
    }
}