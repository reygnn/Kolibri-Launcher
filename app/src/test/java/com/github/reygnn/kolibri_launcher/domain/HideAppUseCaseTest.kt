package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
class HideAppUseCaseTest {

    @Mock
    private lateinit var repository: HiddenAppsRepository

    private lateinit var useCase: HideAppUseCase

    // Test-Daten
    private val testApp = AppInfo(
        originalName = "Test App",
        displayName = "Test App",
        packageName = "com.test.app",
        className = "MainActivity"
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = HideAppUseCase(repository)
    }

    @Test
    fun `invoke - calls hideComponent with correct componentName`() = runTest {
        // Act
        useCase(testApp)

        // Assert
        verify(repository).hideComponent(testApp.componentName)
    }
}