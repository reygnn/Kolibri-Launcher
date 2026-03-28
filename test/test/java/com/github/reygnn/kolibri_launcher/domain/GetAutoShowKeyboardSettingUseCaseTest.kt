package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
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
class GetAutoShowKeyboardSettingUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetAutoShowKeyboardSettingUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetAutoShowKeyboardSettingUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns value from repository flow`() = runTest {
        // Arrange
        val expectedValue = true
        whenever(settingsRepository.autoShowKeyboardFlow).thenReturn(flowOf(expectedValue))

        // Act
        val result = useCase()

        // Assert
        assertTrue(result)
    }

    @Test
    fun `invoke - handles exception gracefully and returns false`() = runTest {
        // Arrange
        whenever(settingsRepository.autoShowKeyboardFlow).thenReturn(flow {
            throw RuntimeException("Database error")
        })

        // Act
        val result = useCase()

        // Assert
        assertFalse(result) // Fallback value
    }
}