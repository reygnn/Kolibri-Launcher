package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckAppUsageUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var appUsageRepository: FakeAppUsageRepository
    private lateinit var useCase: CheckAppUsageUseCase

    @Before
    fun setup() {
        appUsageRepository = FakeAppUsageRepository()
        useCase = CheckAppUsageUseCase(appUsageRepository)
    }

    // =========================================================================
    // Null-Handling
    // =========================================================================

    @Test
    fun `invoke returns false for null packageName`() = runTest {
        // Act
        val result = useCase(null)

        // Assert
        assertThat(result).isFalse()
    }

    // =========================================================================
    // Keine Nutzungsdaten vorhanden
    // =========================================================================

    @Test
    fun `invoke returns false when no usage data exists`() = runTest {
        // Arrange: Keine Daten im Repository

        // Act
        val result = useCase("com.unknown.app")

        // Assert
        assertThat(result).isFalse()
    }

    // =========================================================================
    // Nutzungsdaten vorhanden
    // =========================================================================

    @Test
    fun `invoke returns true when usage data exists`() = runTest {
        // Arrange
        appUsageRepository.recordPackageLaunch("com.known.app")

        // Act
        val result = useCase("com.known.app")

        // Assert
        assertThat(result).isTrue()
    }

    @Test
    fun `invoke returns true only for correct package`() = runTest {
        // Arrange
        appUsageRepository.recordPackageLaunch("com.app1")
        appUsageRepository.recordPackageLaunch("com.app2")

        // Act & Assert
        assertThat(useCase("com.app1")).isTrue()
        assertThat(useCase("com.app2")).isTrue()
        assertThat(useCase("com.app3")).isFalse()
    }

    // =========================================================================
    // Nach Entfernen der Daten
    // =========================================================================

    @Test
    fun `invoke returns false after usage data removed`() = runTest {
        // Arrange
        appUsageRepository.recordPackageLaunch("com.test.app")
        assertThat(useCase("com.test.app")).isTrue()

        // Act
        appUsageRepository.removeUsageDataForPackage("com.test.app")

        // Assert
        assertThat(useCase("com.test.app")).isFalse()
    }
}