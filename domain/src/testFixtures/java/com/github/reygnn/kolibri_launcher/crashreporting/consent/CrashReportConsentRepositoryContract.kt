package com.github.reygnn.kolibri_launcher.crashreporting.consent

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
 * CRASH-REPORT CONSENT REPOSITORY — CONTRACT TEST (tri-state)
 * ============================================================================
 *
 * Pins the success-shape invariants the whole consent flow depends on, shared
 * by the fake and the impl:
 *
 *  1. A fresh repository reports [ConsentDecision.NeverAsked] — neither consent
 *     nor asked. This is the privacy-by-default state that makes the dialog
 *     appear on first launch and keeps ACRA disabled until the user opts in.
 *  2. [CrashReportConsentRepository.setConsent] records the choice as a single
 *     tri-state value: `true` → [ConsentDecision.Granted], `false` →
 *     [ConsentDecision.Denied]. Declining still counts as "asked"
 *     ([hasAsked] stays true), so the dialog does not re-appear every launch
 *     after a "no".
 *  3. [CrashReportConsentRepository.readState] agrees with the individual
 *     getters across every state — the combined read must not drift from
 *     `hasConsent()` / `hasAsked()`.
 *  4. A successful [setConsent] reports [ConsentWriteResult.Saved], a
 *     successful [readState] reports [ConsentReadResult.Loaded] with the stored
 *     decision — the happy-path outcomes both impls share.
 *
 * NOT IN CONTRACT — intentional drifts:
 *   - FAILURE handling only. The impl reports [ConsentReadResult.Unavailable]
 *     on a failed or unknown-token [readState] and [ConsentWriteResult.Failed]
 *     on a write error; the fake never fails, so those are impl-only I/O
 *     details pinned by `CrashReportConsentRepositoryImplTest`, not the
 *     observable contract. The *shape* of both results on success IS in the
 *     contract (see #4 above); only the failure branches are out.
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
    fun `fresh repository is NeverAsked`() = runTest {
        val repo = createRepository()
        assertEquals(ConsentReadResult.Loaded(ConsentDecision.NeverAsked), repo.readState())
        assertFalse(repo.hasConsent())
        assertFalse(repo.hasAsked())
    }

    // ---------- setConsent roundtrip ----------

    @Test
    fun `setConsent true records Granted and marks asked`() = runTest {
        val repo = createRepository()
        assertEquals(ConsentWriteResult.Saved, repo.setConsent(true))
        assertEquals(ConsentReadResult.Loaded(ConsentDecision.Granted), repo.readState())
        assertTrue(repo.hasConsent())
        assertTrue(repo.hasAsked())
    }

    @Test
    fun `setConsent false records Denied but still marks asked`() = runTest {
        val repo = createRepository()
        assertEquals(ConsentWriteResult.Saved, repo.setConsent(false))
        assertEquals(ConsentReadResult.Loaded(ConsentDecision.Denied), repo.readState())
        assertFalse(repo.hasConsent())
        assertTrue(repo.hasAsked())
    }

    @Test
    fun `setConsent overwrites a previous choice`() = runTest {
        val repo = createRepository()
        repo.setConsent(true)
        repo.setConsent(false)
        assertEquals(ConsentReadResult.Loaded(ConsentDecision.Denied), repo.readState())
        assertFalse(repo.hasConsent())
        assertTrue(repo.hasAsked())
    }

    // ---------- combined read (readState) agrees with getters ----------

    @Test
    fun `readState agrees with the individual getters across states`() = runTest {
        val repo = createRepository()

        // Fresh: privacy-by-default.
        repo.assertDecision(ConsentDecision.NeverAsked)

        // After consenting.
        repo.setConsent(true)
        repo.assertDecision(ConsentDecision.Granted)

        // After declining: asked stays true, consent flips off.
        repo.setConsent(false)
        repo.assertDecision(ConsentDecision.Denied)
    }

    /**
     * Asserts [readState] reports [ConsentReadResult.Loaded] with [expected]
     * and that the two plain getters agree with it. An
     * [ConsentReadResult.Unavailable] here would mean the happy path itself
     * lost the state, which no impl may do (A2).
     */
    private suspend fun CrashReportConsentRepository.assertDecision(expected: ConsentDecision) {
        val result = readState()
        assertEquals(ConsentReadResult.Loaded(expected), result)
        assertEquals(expected == ConsentDecision.Granted, hasConsent())
        assertEquals(expected != ConsentDecision.NeverAsked, hasAsked())
    }
}
