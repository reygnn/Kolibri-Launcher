package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * CRASH-REPORT CONSENT REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Pins the two invariants the whole consent flow depends on:
 *
 *  1. A fresh repository reports NEITHER consent NOR asked — this is the
 *     privacy-by-default state that makes the dialog appear on first launch
 *     and keeps ACRA disabled until the user opts in.
 *  2. [CrashReportConsentRepository.setConsent] records the choice AND marks
 *     the dialog as asked in one write — declining still counts as "asked",
 *     so the dialog does not re-appear every launch after a "no".
 *
 * NOT IN CONTRACT — intentional drifts:
 *   - Error handling. The impl logs read/write failures via the DataStore
 *     safety envelope and falls back to `false`; the fake never fails. That
 *     is implementation detail, not part of the observable contract.
 *
 * @see FakeCrashReportConsentRepositoryContractTest
 * @see CrashReportConsentRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CrashReportConsentRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): CrashReportConsentRepository

    // ---------- Initial state (privacy-by-default) ----------

    @Test
    fun `fresh repository has neither consent nor asked`() = runTest {
        val repo = createRepository()
        assertFalse(repo.hasConsent())
        assertFalse(repo.hasAsked())
    }

    // ---------- setConsent roundtrip ----------

    @Test
    fun `setConsent true records consent and marks asked`() = runTest {
        val repo = createRepository()
        repo.setConsent(true)
        assertTrue(repo.hasConsent())
        assertTrue(repo.hasAsked())
    }

    @Test
    fun `setConsent false still marks asked`() = runTest {
        val repo = createRepository()
        repo.setConsent(false)
        assertFalse(repo.hasConsent())
        assertTrue(repo.hasAsked())
    }

    @Test
    fun `setConsent overwrites a previous choice`() = runTest {
        val repo = createRepository()
        repo.setConsent(true)
        repo.setConsent(false)
        assertFalse(repo.hasConsent())
        assertTrue(repo.hasAsked())
    }
}
