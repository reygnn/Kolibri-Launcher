package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
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
class SetTextShadowEnabledUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: SetTextShadowEnabledUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetTextShadowEnabledUseCase(settingsRepository)
    }

    @Test
    fun `invoke - calls setTextShadowEnabled with correct value`() = runTest {
        useCase(true)
        coVerify { settingsRepository.setTextShadowEnabled(true) }
    }
}
