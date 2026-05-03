package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class ResetAppUsageUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var appUsageRepository: AppUsageRepository

    private lateinit var useCase: ResetAppUsageUseCase

    private val testApp = AppInfo(
        originalName = "Test App", displayName = "Test App",
        packageName = "com.test.app", className = "MainActivity",
        isSystemApp = false, isFavorite = false
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ResetAppUsageUseCase(appUsageRepository)
    }

    @Test
    fun `invoke - delegates correctly to repository`() = runTest {
        coEvery { appUsageRepository.removeUsageDataForPackage(testApp.packageName) } returns Unit

        useCase(testApp)

        coVerify { appUsageRepository.removeUsageDataForPackage(testApp.packageName) }
    }

    @Test
    fun `invoke - when repository throws exception - logs and rethrows`() = runTest {
        val expectedError = RuntimeException("Database error")
        coEvery { appUsageRepository.removeUsageDataForPackage(testApp.packageName) } throws expectedError

        assertFailsWith<RuntimeException> { useCase(testApp) }

        coVerify { appUsageRepository.removeUsageDataForPackage(testApp.packageName) }
    }
}
