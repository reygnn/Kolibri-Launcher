package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
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
class SetTextColorUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: SetTextColorUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = SetTextColorUseCase(settingsRepository)
    }

    @Test
    fun `invoke - calls setTextColor with correct color`() = runTest {
        // Arrange
        val testColor = -1 // Beispiel: Color.WHITE

        // Act
        useCase(testColor)

        // Assert
        verify(settingsRepository).setTextColor(testColor)
    }
}