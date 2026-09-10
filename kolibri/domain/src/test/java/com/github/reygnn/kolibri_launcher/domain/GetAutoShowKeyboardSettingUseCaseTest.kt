package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class GetAutoShowKeyboardSettingUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetAutoShowKeyboardSettingUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetAutoShowKeyboardSettingUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns value from repository flow`() = runTest {
        // Arrange
        every { settingsRepository.autoShowKeyboardFlow } returns flowOf(true)

        // Act
        val result = useCase()

        // Assert
        assertTrue(result)
    }

    @Test
    fun `invoke - handles exception gracefully and returns false`() = runTest {
        // Arrange
        every { settingsRepository.autoShowKeyboardFlow } returns flow {
            throw RuntimeException("Database error")
        }

        // Act
        val result = useCase()

        // Assert
        assertFalse(result)
    }
}
