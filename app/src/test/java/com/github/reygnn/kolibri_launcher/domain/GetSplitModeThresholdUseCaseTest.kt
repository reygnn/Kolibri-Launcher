package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertSame

@ExperimentalCoroutinesApi
class GetSplitModeThresholdUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetSplitModeThresholdUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetSplitModeThresholdUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns splitModeThresholdFlow from repository`() = runTest {
        val expectedFlow = MutableStateFlow(5)
        every { settingsRepository.splitModeThresholdFlow } returns expectedFlow

        val result = useCase()

        assertSame(expectedFlow, result)
    }
}
