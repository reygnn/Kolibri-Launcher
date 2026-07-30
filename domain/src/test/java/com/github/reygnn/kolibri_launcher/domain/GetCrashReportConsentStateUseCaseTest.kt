package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.ConsentReadResult
import com.github.reygnn.kolibri_launcher.domain.model.CrashReportConsentState
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentStateUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * The use case is a thin pass-through, but the [ConsentReadResult.Unavailable]
 * half of it is load-bearing: re-collapsing an unreadable store into a
 * `Loaded(all-false)` HERE would reinstate AUDIT-10 #2 one layer above the
 * repository — the startup gate would answer with the consent dialog and write
 * back over a decision the user already made. Nothing else would notice: the
 * repository tests stop below this class and the controller test mocks it.
 */
@ExperimentalCoroutinesApi
class GetCrashReportConsentStateUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: CrashReportConsentRepository

    private lateinit var useCase: GetCrashReportConsentStateUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetCrashReportConsentStateUseCase(repository)
    }

    @Test
    fun `invoke - forwards Loaded unchanged`() = runTest {
        val loaded = ConsentReadResult.Loaded(CrashReportConsentState(hasConsent = true, hasAsked = true))
        coEvery { repository.readState() } returns loaded

        assertEquals(loaded, useCase())
    }

    @Test
    fun `invoke - forwards Unavailable instead of collapsing it into a state`() = runTest {
        val boom = IOException("read failed")
        coEvery { repository.readState() } returns ConsentReadResult.Unavailable(boom)

        assertEquals(ConsentReadResult.Unavailable(boom), useCase())
    }
}
