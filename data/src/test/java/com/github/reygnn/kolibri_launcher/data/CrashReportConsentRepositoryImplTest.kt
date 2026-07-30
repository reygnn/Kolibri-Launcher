package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.reygnn.kolibri_launcher.domain.model.ConsentReadResult
import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Impl-specific SAD-PATH tests for [CrashReportConsentRepositoryImpl].
 *
 * The happy-path behaviour (set/get roundtrips) is pinned by
 * [CrashReportConsentRepositoryImplContractTest]. This file covers the
 * error envelope the contract deliberately leaves out (see the contract's
 * "NOT IN CONTRACT" note):
 *   - a failed `hasConsent`/`hasAsked` read falls back to `false` (never
 *     throws), so a corrupt/unreadable store keeps ACRA off (privacy-safe
 *     default);
 *   - a failed [CrashReportConsentRepositoryImpl.readState] does not throw
 *     and reports [ConsentReadResult.Unavailable] instead of the all-false
 *     state, so the startup gate can tell "unreadable" from "never asked"
 *     (AUDIT-10 #2);
 *   - a failed write does not throw and reports [ConsentWriteResult.Failed]
 *     (best-effort persistence that is observable, not silently swallowed —
 *     AUDIT-10 #11);
 *   - `CancellationException` is re-thrown on BOTH paths, never collapsed
 *     into the generic fallback — coroutine cancellation must propagate.
 */
@ExperimentalCoroutinesApi
class CrashReportConsentRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var repository: CrashReportConsentRepositoryImpl

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        repository = CrashReportConsentRepositoryImpl(fakeDataStore)
    }

    @Test
    fun `hasConsent and hasAsked - when read fails - fall back to false`() = runTest {
        fakeDataStore.makeReadFail()

        assertFalse(repository.hasConsent())
        assertFalse(repository.hasAsked())
    }

    @Test
    fun `readState - when read fails - reports Unavailable`() = runTest {
        fakeDataStore.makeReadFail()

        // Must not throw, and must not collapse into the all-false state:
        // "unreadable" has to stay distinguishable from "never asked", or the
        // startup gate would show a dialog that writes back over a stored
        // decision (AUDIT-10 #2).
        val result = repository.readState()

        assertIs<ConsentReadResult.Unavailable>(result)
    }

    @Test
    fun `readState - on a readable store - reports Loaded`() = runTest {
        repository.setConsent(true)

        val result = repository.readState()

        assertIs<ConsentReadResult.Loaded>(result)
        assertTrue(result.state.hasConsent)
        assertTrue(result.state.hasAsked)
    }

    @Test
    fun `readState - when read is cancelled - propagates CancellationException`() = runTest {
        val cancellingStore = mockk<DataStore<Preferences>>()
        every { cancellingStore.data } returns flow {
            throw CancellationException("simulated read cancellation")
        }
        val repo = CrashReportConsentRepositoryImpl(cancellingStore)

        assertFailsWith<CancellationException> {
            repo.readState()
        }
    }

    @Test
    fun `setConsent - when edit fails - reports Failed and persists nothing`() = runTest {
        fakeDataStore.makeEditFail()

        // Must not throw despite the underlying edit failing, and must report
        // the failure rather than a false "saved" (AUDIT-10 #11).
        val result = repository.setConsent(true)

        assertIs<ConsentWriteResult.Failed>(result)
        // Read path is unaffected by the edit-fail flag; nothing was stored.
        assertFalse(repository.hasConsent())
    }

    @Test
    fun `setConsent - on a successful write - reports Saved`() = runTest {
        val result = repository.setConsent(true)

        assertTrue(result is ConsentWriteResult.Saved)
        assertTrue(repository.hasConsent())
    }

    @Test
    fun `setConsent - when write is cancelled - propagates CancellationException`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            repository.setConsent(true)
        }
    }

    @Test
    fun `hasConsent - when read is cancelled - propagates CancellationException`() = runTest {
        // FakeDataStore only cancels its write path, so inject a cancelling
        // read via a mock (same idiom as the SettingsRepositoryImpl doomsday
        // tests). Guards that the CancellationException catch is ordered
        // BEFORE the generic Exception fallback — otherwise cancellation would
        // be swallowed and hasConsent() would wrongly return false.
        val cancellingStore = mockk<DataStore<Preferences>>()
        every { cancellingStore.data } returns flow {
            throw CancellationException("simulated read cancellation")
        }
        val repo = CrashReportConsentRepositoryImpl(cancellingStore)

        assertFailsWith<CancellationException> {
            repo.hasConsent()
        }
    }
}
