package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class ResetAppUsageUseCaseTest {

    @Mock
    private lateinit var appUsageRepository: AppUsageRepository

    private lateinit var useCase: ResetAppUsageUseCase

    // Dummy AppInfo für die Tests, angepasst an die neue AppInfo Definition
    private val testApp = AppInfo(
        originalName = "Test App",
        displayName = "Test App",
        packageName = "com.test.app",
        className = "MainActivity",
        isSystemApp = false,
        isFavorite = false
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = ResetAppUsageUseCase(appUsageRepository)
    }

    @Test
    fun `invoke - delegates correctly to repository`() = runTest {
        // Act
        useCase(testApp)

        // Assert
        verify(appUsageRepository).removeUsageDataForPackage(testApp.packageName)
    }

    @Test
    fun `invoke - when repository throws exception - logs and rethrows`() = runTest {
        // Arrange
        val expectedError = RuntimeException("Database error")
        whenever(appUsageRepository.removeUsageDataForPackage(testApp.packageName)).thenThrow(expectedError)

        // Act & Assert
        // Wir erwarten, dass der Fehler nach dem Logging wieder geworfen wird
        assertFailsWith<RuntimeException> {
            useCase(testApp)
        }

        verify(appUsageRepository).removeUsageDataForPackage(testApp.packageName)
    }
}