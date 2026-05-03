package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class RecordAppLaunchUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var repository: AppUsageRepository

    private lateinit var useCase: RecordAppLaunchUseCase

    private val testApp = AppInfo(
        originalName = "Test App", displayName = "Test App",
        packageName = "com.test.app", className = "MainActivity"
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = RecordAppLaunchUseCase(repository)
    }

    @Test
    fun `invoke - calls recordPackageLaunch with correct packageName`() = runTest {
        useCase(testApp)
        coVerify { repository.recordPackageLaunch(testApp.packageName) }
    }
}
