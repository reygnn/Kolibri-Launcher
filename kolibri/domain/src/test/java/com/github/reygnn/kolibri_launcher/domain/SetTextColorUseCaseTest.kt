package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
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
class SetTextColorUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: SetTextColorUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetTextColorUseCase(settingsRepository)
    }

    @Test
    fun `invoke - calls setTextColor with correct color`() = runTest {
        useCase(-1)
        coVerify { settingsRepository.setTextColor(-1) }
    }
}
