package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
class SetChipBackgroundColorUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: SetChipBackgroundColorUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = SetChipBackgroundColorUseCase(settingsRepository)
    }

    @Test
    fun `invoke - calls setChipBackgroundColor with correct color`() = runTest {
        // Arrange
        val testColor = -16777216 // Beispiel: Color.BLACK

        // Act
        useCase(testColor)

        // Assert
        verify(settingsRepository).setChipBackgroundColor(testColor)
    }
}