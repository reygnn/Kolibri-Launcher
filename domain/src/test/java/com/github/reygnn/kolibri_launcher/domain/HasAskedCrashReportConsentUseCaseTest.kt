package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.HasAskedCrashReportConsentUseCase
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
class HasAskedCrashReportConsentUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: CrashReportConsentRepository

    private lateinit var useCase: HasAskedCrashReportConsentUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = HasAskedCrashReportConsentUseCase(repository)
    }

    @Test
    fun `invoke - returns true when already asked`() = runTest {
        coEvery { repository.hasAsked() } returns true
        assertTrue(useCase())
    }

    @Test
    fun `invoke - returns false when not yet asked`() = runTest {
        coEvery { repository.hasAsked() } returns false
        assertFalse(useCase())
    }
}
