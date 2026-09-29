package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.FactoryResetUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class FactoryResetUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var resetRepository: ResetRepository
    @MockK
    private lateinit var installedAppsRepository: InstalledAppsRepository

    private lateinit var useCase: FactoryResetUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = FactoryResetUseCase(resetRepository, installedAppsRepository)
    }

    @Test
    fun `invoke - with usage data - calls all reset methods and triggers update on success`() = runTest {
        // Arrange
        coEvery { resetRepository.resetSettings() } returns true
        coEvery { resetRepository.resetUserData() } returns true
        coEvery { resetRepository.resetAppUsageData() } returns true
        coEvery { installedAppsRepository.triggerAppsUpdate() } returns Unit

        // Act
        val result = useCase(includeUsageData = true)

        // Assert
        assertThat(result).isEqualTo(FactoryResetUseCase.Result.Success)
        coVerify { resetRepository.resetSettings() }
        coVerify { resetRepository.resetUserData() }
        coVerify { resetRepository.resetAppUsageData() }
        coVerify { installedAppsRepository.triggerAppsUpdate() }
    }

    @Test
    fun `invoke - without usage data - skips app usage reset`() = runTest {
        // Arrange
        coEvery { resetRepository.resetSettings() } returns true
        coEvery { resetRepository.resetUserData() } returns true
        coEvery { installedAppsRepository.triggerAppsUpdate() } returns Unit

        // Act
        val result = useCase(includeUsageData = false)

        // Assert
        assertThat(result).isEqualTo(FactoryResetUseCase.Result.Success)
        coVerify { resetRepository.resetSettings() }
        coVerify { resetRepository.resetUserData() }
        coVerify(exactly = 0) { resetRepository.resetAppUsageData() }
        coVerify { installedAppsRepository.triggerAppsUpdate() }
    }

    @Test
    fun `invoke - when one reset fails - returns PartialFailure and does not trigger update`() = runTest {
        // Arrange
        coEvery { resetRepository.resetSettings() } returns true
        coEvery { resetRepository.resetUserData() } returns false
        coEvery { resetRepository.resetAppUsageData() } returns true

        // Act
        val result = useCase(includeUsageData = true)

        // Assert
        assertThat(result).isEqualTo(FactoryResetUseCase.Result.PartialFailure)
        coVerify(exactly = 0) { installedAppsRepository.triggerAppsUpdate() }
    }

    @Test
    fun `invoke - when exception occurs - returns Error`() = runTest {
        // Arrange
        coEvery { resetRepository.resetSettings() } throws RuntimeException("DB Error")

        // Act
        val result = useCase(includeUsageData = false)

        // Assert
        assertThat(result).isEqualTo(FactoryResetUseCase.Result.Error)
    }
}
