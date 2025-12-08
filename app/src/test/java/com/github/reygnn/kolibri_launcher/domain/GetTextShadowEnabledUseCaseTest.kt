package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
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
class GetTextShadowEnabledUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetTextShadowEnabledUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetTextShadowEnabledUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns value from repository flow`() = runTest {
        // Arrange
        val expectedValue = false // Testwert, der vom Default (true) abweicht
        whenever(settingsRepository.textShadowEnabledFlow).thenReturn(flowOf(expectedValue))

        // Act
        val result = useCase()

        // Assert
        assertFalse(result)
    }

    @Test
    fun `invoke - handles exception gracefully and returns true`() = runTest {
        // Arrange
        whenever(settingsRepository.textShadowEnabledFlow).thenReturn(flow {
            throw RuntimeException("Database error")
        })

        // Act
        val result = useCase()

        // Assert
        assertTrue(result) // Erwarteter Fallback-Wert ist true
    }
}