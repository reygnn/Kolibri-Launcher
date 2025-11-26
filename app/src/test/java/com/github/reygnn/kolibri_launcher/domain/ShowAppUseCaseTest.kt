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
class ShowAppUseCaseTest {

    @Mock
    private lateinit var repository: HiddenAppsRepository

    private lateinit var useCase: ShowAppUseCase

    // Test-Daten: Wichtig ist, dass componentName korrekt berechnet/genutzt wird
    private val testApp = AppInfo(
        originalName = "Test App",
        displayName = "Test App",
        packageName = "com.test.app",
        className = "MainActivity"
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = ShowAppUseCase(repository)
    }

    @Test
    fun `invoke - calls showComponent with correct componentName`() = runTest {
        // Act
        useCase(testApp)

        // Assert
        // Hier prüfen wir explizit, ob 'componentName' verwendet wurde
        // (und nicht versehentlich nur der packageName)
        verify(repository).showComponent(testApp.componentName)
    }
}