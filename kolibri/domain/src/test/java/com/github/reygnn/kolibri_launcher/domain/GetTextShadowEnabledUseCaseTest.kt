package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
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

@ExperimentalCoroutinesApi
class GetTextShadowEnabledUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: GetTextShadowEnabledUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetTextShadowEnabledUseCase(settingsRepository)
    }

    @Test
    fun `invoke - returns value from repository flow`() = runTest {
        every { settingsRepository.textShadowEnabledFlow } returns flowOf(false)
        assertThat(useCase()).isFalse()
    }

    @Test
    fun `invoke - handles exception gracefully and returns true`() = runTest {
        every { settingsRepository.textShadowEnabledFlow } returns flow {
            throw RuntimeException("Database error")
        }
        assertThat(useCase()).isTrue()
    }
}
