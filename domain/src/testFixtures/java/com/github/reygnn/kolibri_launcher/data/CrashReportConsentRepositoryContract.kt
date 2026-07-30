package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 *  3. [CrashReportConsentRepository.readState] returns exactly the same two
 *     flags as the individual getters — the combined read must not drift
 *     from `hasConsent()` / `hasAsked()` (AUDIT-10 #4).
 *  4. A successful [CrashReportConsentRepository.setConsent] reports
 *     [ConsentWriteResult.Saved] — the happy-path outcome both impls share.
 *
 * NOT IN CONTRACT — intentional drifts:
 *   - FAILURE handling only. The impl fails closed on reads (privacy-safe
 *     `false`) and reports [ConsentWriteResult.Failed] on a write error; the
 *     fake never fails, so those are impl-only I/O details pinned by
 *     `CrashReportConsentRepositoryImplTest`, not the observable contract.
 *     The *shape* of the write result (Saved on success) IS in the contract
 *     (see #4 above); only the Failed branch is out.
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
    fun `setConsent reports Saved on a successful write`() = runTest {
        val repo = createRepository()
        assertEquals(ConsentWriteResult.Saved, repo.setConsent(true))
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

    // ---------- combined read (readState) ----------

    @Test
    fun `readState agrees with the individual getters across states`() = runTest {
        val repo = createRepository()

        // Fresh: privacy-by-default.
        repo.readState().let { state ->
            assertFalse(state.hasConsent)
            assertFalse(state.hasAsked)
            assertEquals(repo.hasConsent(), state.hasConsent)
            assertEquals(repo.hasAsked(), state.hasAsked)
        }

        // After consenting.
        repo.setConsent(true)
        repo.readState().let { state ->
            assertTrue(state.hasConsent)
            assertTrue(state.hasAsked)
            assertEquals(repo.hasConsent(), state.hasConsent)
            assertEquals(repo.hasAsked(), state.hasAsked)
        }

        // After declining: asked stays true, consent flips off.
        repo.setConsent(false)
        repo.readState().let { state ->
            assertFalse(state.hasConsent)
            assertTrue(state.hasAsked)
            assertEquals(repo.hasConsent(), state.hasConsent)
            assertEquals(repo.hasAsked(), state.hasAsked)
        }
    }
}
