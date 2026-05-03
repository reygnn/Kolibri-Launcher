package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
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
class ShowAppUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxed = true)
    private lateinit var repository: HiddenAppsRepository

    private lateinit var useCase: ShowAppUseCase

    private val testApp = AppInfo(
        originalName = "Test App", displayName = "Test App",
        packageName = "com.test.app", className = "MainActivity"
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ShowAppUseCase(repository)
    }

    @Test
    fun `invoke - calls showComponent with correct componentName`() = runTest {
        useCase(testApp)
        coVerify { repository.showComponent(testApp.componentName) }
    }
}