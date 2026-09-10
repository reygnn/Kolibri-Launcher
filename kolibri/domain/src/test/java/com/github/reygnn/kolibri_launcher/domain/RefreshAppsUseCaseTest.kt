package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
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
class RefreshAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var repository: InstalledAppsRepository

    private lateinit var useCase: RefreshAppsUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = RefreshAppsUseCase(repository)
    }

    @Test
    fun `invoke - calls triggerAppsUpdate on repository`() = runTest {
        useCase()
        coVerify { repository.triggerAppsUpdate() }
    }
}
