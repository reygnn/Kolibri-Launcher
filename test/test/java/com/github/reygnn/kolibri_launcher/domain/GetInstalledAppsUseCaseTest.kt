package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
class GetInstalledAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var installedAppsRepository: InstalledAppsRepository

    private lateinit var useCase: GetInstalledAppsUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetInstalledAppsUseCase(installedAppsRepository)
    }

    @Test
    fun `invoke - delegates to repository and emits apps`() = runTest {
        // Arrange
        val testApps = listOf(
            AppInfo("App A", "App A", "pkg.a", "cls.a"),
            AppInfo("App B", "App B", "pkg.b", "cls.b")
        )
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))

        // Act
        useCase().test {
            // Assert
            val result = awaitItem()
            assertEquals(2, result.size)
            assertEquals("App A", result[0].displayName)
            awaitComplete()
        }

        verify(installedAppsRepository).getInstalledApps()
    }
}