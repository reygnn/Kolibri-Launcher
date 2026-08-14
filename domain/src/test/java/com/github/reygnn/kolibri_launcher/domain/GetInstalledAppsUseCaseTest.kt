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
        // No custom names: applyCustomNames sets displayName = originalName. A COMPLETING
        // flow (flowOf), so the combined flow still completes for awaitComplete().
        every { customNamesRepository.customNamesFlow } returns flowOf(emptyMap())
        useCase = GetInstalledAppsUseCase(installedAppsRepository, customNamesRepository)
    }

    @Test
    fun `invoke - unwraps Loaded and PRESERVES input order (map-only, no sort)`() = runTest {
        // Deliberately non-alphabetical input: post RAL-4 map-only the use case does
        // NOT sort, so the output must stay in the SAME order (a sort would flip it
        // to [Apple, Zebra]). This pins the unsortedInstalledAppsFlow contract.
        val testApps = listOf(
            AppInfo("Zebra", "Zebra", "pkg.z", "cls.z"),
            AppInfo("Apple", "Apple", "pkg.a", "cls.a")
        )
        every { installedAppsRepository.getInstalledApps() } returns flowOf(AppLoad.Loaded(testApps))

        useCase.unsortedInstalledAppsFlow.test {
            val result = awaitItem()
            assertEquals(listOf("Zebra", "Apple"), result.map { it.displayName })
            awaitComplete()
        }

        verify { installedAppsRepository.getInstalledApps() }
    }

    @Test
    fun `invoke - unwraps Failed to an empty list (compat boundary)`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns
            flowOf(AppLoad.Failed(RuntimeException("boom")))

        useCase.unsortedInstalledAppsFlow.test {
            assertEquals(emptyList(), awaitItem())
            awaitComplete()
        }
    }
}
