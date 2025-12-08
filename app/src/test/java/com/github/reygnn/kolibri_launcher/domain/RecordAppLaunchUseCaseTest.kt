package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
class RecordAppLaunchUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: AppUsageRepository

    private lateinit var useCase: RecordAppLaunchUseCase

    private val testApp = AppInfo(
        originalName = "Test App",
        displayName = "Test App",
        packageName = "com.test.app",
        className = "MainActivity"
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = RecordAppLaunchUseCase(repository)
    }

    @Test
    fun `invoke - calls recordPackageLaunch with correct packageName`() = runTest {
        // Act
        useCase(testApp)

        // Assert
        verify(repository).recordPackageLaunch(testApp.packageName)
    }
}