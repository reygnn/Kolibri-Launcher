package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeScreenLockRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestNotificationsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var screenLockRepository: FakeScreenLockRepository
    private lateinit var useCase: RequestNotificationsUseCase

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        screenLockRepository = FakeScreenLockRepository()
        useCase = RequestNotificationsUseCase(settingsRepository, screenLockRepository)
    }

    // =========================================================================
    // Erfolgsfall
    // =========================================================================

    @Test
    fun `invoke returns Success when enabled and available`() = runTest {
        // Arrange
        settingsRepository.setSwipeDownToNotificationsEnabled(true)
        screenLockRepository.setServiceState(true)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestNotificationsUseCase.Result.Success)
    }

    // =========================================================================
    // ErrorAccessibility
    // =========================================================================

    @Test
    fun `invoke returns ErrorAccessibility when enabled but not available`() = runTest {
        // Arrange
        settingsRepository.setSwipeDownToNotificationsEnabled(true)
        screenLockRepository.setServiceState(false)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestNotificationsUseCase.Result.ErrorAccessibility)
    }

    // =========================================================================
    // ErrorDisabled
    // =========================================================================

    @Test
    fun `invoke returns ErrorDisabled when setting is off`() = runTest {
        // Arrange
        settingsRepository.setSwipeDownToNotificationsEnabled(false)
        screenLockRepository.setServiceState(true)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestNotificationsUseCase.Result.ErrorDisabled)
    }

    @Test
    fun `invoke returns ErrorDisabled even when accessibility unavailable`() = runTest {
        // Arrange: Beide aus - Disabled hat Priorität
        settingsRepository.setSwipeDownToNotificationsEnabled(false)
        screenLockRepository.setServiceState(false)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestNotificationsUseCase.Result.ErrorDisabled)
    }
}