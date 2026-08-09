package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
class GetInstalledAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var installedAppsRepository: InstalledAppsRepository

    @MockK
    private lateinit var customNamesRepository: CustomNamesRepository

    private lateinit var useCase: GetInstalledAppsUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        // No custom names: applyNames sets displayName = originalName. A COMPLETING
        // flow (flowOf), so the combined flow still completes for awaitComplete().
        every { customNamesRepository.customNamesFlow } returns flowOf(emptyMap())
        useCase = GetInstalledAppsUseCase(installedAppsRepository, customNamesRepository)
    }

    @Test
    fun `invoke - unwraps Loaded to the app list`() = runTest {
        val testApps = listOf(
            AppInfo("App A", "App A", "pkg.a", "cls.a"),
            AppInfo("App B", "App B", "pkg.b", "cls.b")
        )
        every { installedAppsRepository.getInstalledApps() } returns flowOf(AppLoad.Loaded(testApps))

        useCase().test {
            val result = awaitItem()
            assertEquals(2, result.size)
            assertEquals("App A", result[0].displayName)
            awaitComplete()
        }

        verify { installedAppsRepository.getInstalledApps() }
    }

    @Test
    fun `invoke - unwraps Failed to an empty list (compat boundary)`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns
            flowOf(AppLoad.Failed(RuntimeException("boom")))

        useCase().test {
            assertEquals(emptyList(), awaitItem())
            awaitComplete()
        }
    }
}
