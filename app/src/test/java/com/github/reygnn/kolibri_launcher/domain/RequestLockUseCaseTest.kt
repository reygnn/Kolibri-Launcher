package com.github.reygnn.kolibri_launcher.domain.usecase

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
class RequestLockUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var screenLockRepository: FakeScreenLockRepository
    private lateinit var useCase: RequestLockUseCase

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        screenLockRepository = FakeScreenLockRepository()
        useCase = RequestLockUseCase(settingsRepository, screenLockRepository)
    }

    // =========================================================================
    // Erfolgsfall
    // =========================================================================

    @Test
    fun `invoke returns Success when enabled and available`() = runTest {
        // Arrange
        settingsRepository.setDoubleTapToLockEnabled(true)
        screenLockRepository.setServiceState(true)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestLockUseCase.Result.Success)
    }

    // =========================================================================
    // ErrorAccessibility
    // =========================================================================

    @Test
    fun `invoke returns ErrorAccessibility when enabled but not available`() = runTest {
        // Arrange
        settingsRepository.setDoubleTapToLockEnabled(true)
        screenLockRepository.setServiceState(false)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestLockUseCase.Result.ErrorAccessibility)
    }

    // =========================================================================
    // ErrorDisabled
    // =========================================================================

    @Test
    fun `invoke returns ErrorDisabled when setting is off`() = runTest {
        // Arrange
        settingsRepository.setDoubleTapToLockEnabled(false)
        screenLockRepository.setServiceState(true)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestLockUseCase.Result.ErrorDisabled)
    }

    @Test
    fun `invoke returns ErrorDisabled even when accessibility unavailable`() = runTest {
        // Arrange: Beide aus - Disabled hat Priorität
        settingsRepository.setDoubleTapToLockEnabled(false)
        screenLockRepository.setServiceState(false)

        // Act
        val result = useCase()

        // Assert
        assertThat(result).isEqualTo(RequestLockUseCase.Result.ErrorDisabled)
    }
}