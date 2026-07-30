package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@ExperimentalCoroutinesApi
class SetCrashReportConsentUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: CrashReportConsentRepository

    private lateinit var useCase: SetCrashReportConsentUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetCrashReportConsentUseCase(repository)
    }

    @Test
    fun `invoke - forwards true to repository`() = runTest {
        coEvery { repository.setConsent(true) } returns ConsentWriteResult.Saved
        useCase(true)
        coVerify { repository.setConsent(true) }
    }

    @Test
    fun `invoke - forwards false to repository`() = runTest {
        coEvery { repository.setConsent(false) } returns ConsentWriteResult.Saved
        useCase(false)
        coVerify { repository.setConsent(false) }
    }

    @Test
    fun `invoke - returns the repository write result`() = runTest {
        // The use case is a thin pass-through: whatever the repository reports
        // (Saved or Failed) must reach the app-scope caller unchanged, so a
        // persist failure stays observable (AUDIT-10 #11).
        coEvery { repository.setConsent(true) } returns ConsentWriteResult.Saved
        assertEquals(ConsentWriteResult.Saved, useCase(true))

        val boom = IOException("edit failed")
        coEvery { repository.setConsent(false) } returns ConsentWriteResult.Failed(boom)
        assertEquals(ConsentWriteResult.Failed(boom), useCase(false))
    }
}
