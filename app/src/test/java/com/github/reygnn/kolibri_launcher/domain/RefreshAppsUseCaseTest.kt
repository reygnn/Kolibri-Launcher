package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
class RefreshAppsUseCaseTest {

    @Mock
    private lateinit var repository: InstalledAppsRepository

    private lateinit var useCase: RefreshAppsUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = RefreshAppsUseCase(repository)
    }

    @Test
    fun `invoke - calls triggerAppsUpdate on repository`() = runTest {
        // Act
        useCase()

        // Assert
        verify(repository).triggerAppsUpdate()
    }
}