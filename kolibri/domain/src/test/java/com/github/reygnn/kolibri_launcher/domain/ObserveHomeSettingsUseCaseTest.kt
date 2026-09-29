package com.github.reygnn.kolibri_launcher.domain.usecase

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
    fun `invoke - maps sortOrder into HomeSettings correctly`() = runTest {
        val sortOrderFlow = MutableStateFlow(SortOrder.ALPHABETICAL)

        every { settingsRepository.sortOrderFlow } returns sortOrderFlow

        useCase().test {
            val initialResult = awaitItem()
            assertThat(initialResult.sortOrder).isEqualTo(SortOrder.ALPHABETICAL)

            sortOrderFlow.value = SortOrder.TIME_WEIGHTED_USAGE

            val updatedResult = awaitItem()
            assertThat(updatedResult.sortOrder).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
        }
    }
}
