package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDoubleTapClipboardSettingUseCase
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
class GetDoubleTapClipboardSettingUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetDoubleTapClipboardSettingUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetDoubleTapClipboardSettingUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns value from repository flow`() = runTest {
        // Arrange
        every { settingsRepository.doubleTapClipboardEnabledFlow } returns flowOf(true)

        // Act
        val result = useCase()

        // Assert
        assertTrue(result)
    }

    @Test
    fun `invoke - returns false when the setting is off`() = runTest {
        // Arrange
        every { settingsRepository.doubleTapClipboardEnabledFlow } returns flowOf(false)

        // Act
        val result = useCase()

        // Assert
        assertFalse(result)
    }

    @Test
    fun `invoke - fails closed, never enabling clipboard reads on a read error`() = runTest {
        // This is the safety property the whole feature rests on: a broken
        // DataStore read must leave the gesture inert, not silently turn a
        // clipboard-reading action on for a user who never opted in.
        // Arrange
        every { settingsRepository.doubleTapClipboardEnabledFlow } returns flow {
            throw RuntimeException("Database error")
        }

        // Act
        val result = useCase()

        // Assert
        assertFalse(result)
    }
}
