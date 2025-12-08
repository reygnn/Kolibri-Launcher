package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.FactoryResetUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
class FactoryResetUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var resetRepository: ResetRepository
    @Mock
    private lateinit var installedAppsRepository: InstalledAppsRepository

    private lateinit var useCase: FactoryResetUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = FactoryResetUseCase(resetRepository, installedAppsRepository)
    }

    @Test
    fun `invoke - with usage data - calls all reset methods and triggers update on success`() = runTest {
        // Arrange
        whenever(resetRepository.resetSettings()).thenReturn(true)
        whenever(resetRepository.resetUserData()).thenReturn(true)
        whenever(resetRepository.resetAppUsageData()).thenReturn(true)

        // Act
        val result = useCase(includeUsageData = true)

        // Assert
        assertEquals(FactoryResetUseCase.Result.Success, result)
        verify(resetRepository).resetSettings()
        verify(resetRepository).resetUserData()
        verify(resetRepository).resetAppUsageData() // Muss aufgerufen werden
        verify(installedAppsRepository).triggerAppsUpdate()
    }

    @Test
    fun `invoke - without usage data - skips app usage reset`() = runTest {
        // Arrange
        whenever(resetRepository.resetSettings()).thenReturn(true)
        whenever(resetRepository.resetUserData()).thenReturn(true)

        // Act
        val result = useCase(includeUsageData = false)

        // Assert
        assertEquals(FactoryResetUseCase.Result.Success, result)
        verify(resetRepository).resetSettings()
        verify(resetRepository).resetUserData()
        verify(resetRepository, never()).resetAppUsageData() // Darf NICHT aufgerufen werden
        verify(installedAppsRepository).triggerAppsUpdate()
    }

    @Test
    fun `invoke - when one reset fails - returns PartialFailure and does not trigger update`() = runTest {
        // Arrange
        whenever(resetRepository.resetSettings()).thenReturn(true)
        whenever(resetRepository.resetUserData()).thenReturn(false) // Schlägt fehl
        // Usage data wird hier irrelevant, da UserData schon false ist, aber wir mocken es safe
        whenever(resetRepository.resetAppUsageData()).thenReturn(true)

        // Act
        val result = useCase(includeUsageData = true)

        // Assert
        assertEquals(FactoryResetUseCase.Result.PartialFailure, result)
        verify(installedAppsRepository, never()).triggerAppsUpdate() // Kein Update triggern bei Fehler
    }

    @Test
    fun `invoke - when exception occurs - returns Error`() = runTest {
        // Arrange
        whenever(resetRepository.resetSettings()).thenThrow(RuntimeException("DB Error"))

        // Act
        val result = useCase(includeUsageData = false)

        // Assert
        assertEquals(FactoryResetUseCase.Result.Error, result)
    }
}