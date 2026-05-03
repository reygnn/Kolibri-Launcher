package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.UpdateHiddenAppsUseCase
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
class UpdateHiddenAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var repository: HiddenAppsRepository

    private lateinit var useCase: UpdateHiddenAppsUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = UpdateHiddenAppsUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository updateComponentVisibilities`() = runTest {
        val toHide = setOf("com.hide/Component")
        val toShow = setOf("com.show/Component")

        useCase(toHide, toShow)

        coVerify { repository.updateComponentVisibilities(toHide, toShow) }
    }
}
