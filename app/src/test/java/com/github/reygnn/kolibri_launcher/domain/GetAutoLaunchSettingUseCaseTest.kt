package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class GetAutoLaunchSettingUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetAutoLaunchSettingUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetAutoLaunchSettingUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns value from repository flow`() = runTest {
        // Arrange
        val expectedValue = true
        whenever(settingsRepository.autoLaunchAppFlow).thenReturn(flowOf(expectedValue))

        // Act
        val result = useCase()

        // Assert
        assertTrue(result)
    }

    @Test
    fun `invoke - handles exception gracefully and returns false`() = runTest {
        // Arrange
        whenever(settingsRepository.autoLaunchAppFlow).thenReturn(flow {
            throw RuntimeException("Database error")
        })

        // Act
        val result = useCase()

        // Assert
        assertFalse(result) // Fallback value
    }
}