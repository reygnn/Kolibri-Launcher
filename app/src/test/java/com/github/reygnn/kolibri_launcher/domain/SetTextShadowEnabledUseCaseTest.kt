package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
class SetTextShadowEnabledUseCaseTest {

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: SetTextShadowEnabledUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = SetTextShadowEnabledUseCase(settingsRepository)
    }

    @Test
    fun `invoke - calls setTextShadowEnabled with correct value`() = runTest {
        // Arrange
        val isEnabled = true

        // Act
        useCase(isEnabled)

        // Assert
        verify(settingsRepository).setTextShadowEnabled(isEnabled)
    }
}