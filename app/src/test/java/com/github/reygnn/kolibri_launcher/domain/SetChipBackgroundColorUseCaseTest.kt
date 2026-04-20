package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class SetChipBackgroundColorUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: SetChipBackgroundColorUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetChipBackgroundColorUseCase(settingsRepository)
    }

    @Test
    fun `invoke - calls setChipBackgroundColor with correct color`() = runTest {
        useCase(-16777216)
        coVerify { settingsRepository.setChipBackgroundColor(-16777216) }
    }
}
