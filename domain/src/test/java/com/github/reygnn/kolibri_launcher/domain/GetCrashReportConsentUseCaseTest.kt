package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetCrashReportConsentUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class GetCrashReportConsentUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: CrashReportConsentRepository

    private lateinit var useCase: GetCrashReportConsentUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetCrashReportConsentUseCase(repository)
    }

    @Test
    fun `invoke - returns true when consent granted`() = runTest {
        coEvery { repository.hasConsent() } returns true
        assertTrue(useCase())
    }

    @Test
    fun `invoke - returns false when consent not granted`() = runTest {
        coEvery { repository.hasConsent() } returns false
        assertFalse(useCase())
    }
}
