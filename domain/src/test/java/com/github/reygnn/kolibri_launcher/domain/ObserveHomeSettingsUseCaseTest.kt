package com.github.reygnn.kolibri_launcher.domain.usecase

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
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
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
class ObserveHomeSettingsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: ObserveHomeSettingsUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ObserveHomeSettingsUseCase(settingsRepository)
    }

    @Test
    fun `invoke - combines flows into HomeSettings correctly`() = runTest {
        val sortOrderFlow = MutableStateFlow(SortOrder.ALPHABETICAL)
        val autoLaunchFlow = MutableStateFlow(true)

        every { settingsRepository.sortOrderFlow } returns sortOrderFlow
        every { settingsRepository.autoLaunchAppFlow } returns autoLaunchFlow

        useCase().test {
            val initialResult = awaitItem()
            assertEquals(SortOrder.ALPHABETICAL, initialResult.sortOrder)
            assertEquals(true, initialResult.autoLaunchApp)

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE

            val updatedResult = awaitItem()
            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, updatedResult.sortOrder)
            assertEquals(true, updatedResult.autoLaunchApp)
        }
    }
}
