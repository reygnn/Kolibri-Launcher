package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCrashReportConsentUseCase
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
class SetCrashReportConsentUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var repository: CrashReportConsentRepository

    private lateinit var useCase: SetCrashReportConsentUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetCrashReportConsentUseCase(repository)
    }

    @Test
    fun `invoke - forwards true to repository`() = runTest {
        useCase(true)
        coVerify { repository.setConsent(true) }
    }

    @Test
    fun `invoke - forwards false to repository`() = runTest {
        useCase(false)
        coVerify { repository.setConsent(false) }
    }
}
