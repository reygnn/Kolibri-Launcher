package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import kotlin.test.assertSame

@ExperimentalCoroutinesApi
class GetSplitModeThresholdUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetSplitModeThresholdUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetSplitModeThresholdUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns splitModeThresholdFlow from repository`() = runTest {
        // Arrange
        val expectedFlow = MutableStateFlow(5)
        whenever(settingsRepository.splitModeThresholdFlow).thenReturn(expectedFlow)

        // Act
        val result = useCase()

        // Assert
        // Wir prüfen auf Referenzgleichheit, da der Flow einfach durchgereicht werden soll
        assertSame(expectedFlow, result)
    }
}