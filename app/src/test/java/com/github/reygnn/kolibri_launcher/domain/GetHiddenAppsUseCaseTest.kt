package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class GetHiddenAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: HiddenAppsRepository

    private lateinit var useCase: GetHiddenAppsUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetHiddenAppsUseCase(repository)
    }

    @Test
    fun `invoke - returns hiddenAppsFlow from repository`() = runTest {
        val expectedSet = setOf("com.app1/Component", "com.app2/Component")
        every { repository.hiddenAppsFlow } returns flowOf(expectedSet)

        useCase().collect { result ->
            assertEquals(expectedSet, result)
        }
        verify { repository.hiddenAppsFlow }
    }
}
