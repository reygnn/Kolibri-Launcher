package com.github.reygnn.kolibri_launcher.crashreporting.consent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Impl-specific SAD-PATH tests for [CrashReportConsentRepositoryImpl].
 *
 * The happy-path behaviour (roundtrips and the success shapes `Loaded`/`Saved`)
 * is pinned by [CrashReportConsentRepositoryImplContractTest] — do not duplicate
 * it here. This file covers ONLY the error envelope the contract deliberately
 * leaves out (its "NOT IN CONTRACT" note), making it the single place that goes
 * red if a failure is ever re-collapsed into a plausible default:
 *   - a failed [CrashReportConsentRepositoryImpl.readState] reports
 *     [ConsentReadResult.Unavailable] instead of a default, so the gate can tell
 *     "unreadable" from "never asked" (A2, AUDIT-10 #2);
 *   - a *present but unknown* token also reports [ConsentReadResult.Unavailable],
 *     never [ConsentDecision.NeverAsked] (SR5);
 *   - a failed write reports [ConsentWriteResult.Failed] and persists nothing
 *     (best-effort but observable, A4, AUDIT-10 #11);
 *   - `CancellationException` is re-thrown on BOTH the read and write paths,
 *     never collapsed into the generic fallback (A6).
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
    fun `readState - when read fails - reports Unavailable`() = runTest {
        fakeDataStore.makeReadFail()

        assertIs<ConsentReadResult.Unavailable>(repository.readState())
    }

    @Test
    fun `readState - when the stored token is unknown - reports Unavailable`() = runTest {
        // A present-but-uninterpretable token must NOT collapse into NeverAsked:
        // we hold a record we cannot read (SR5). Every start would otherwise
        // re-read the same garbage as "never asked" and re-prompt.
        fakeDataStore.setInitialData(preferencesOf(ConsentBootstrap.CONSENT_DECISION_KEY to "BOGUS"))

        assertIs<ConsentReadResult.Unavailable>(repository.readState())
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

        val result = repository.setConsent(true)

        assertIs<ConsentWriteResult.Failed>(result)
        // Read path is unaffected by the edit-fail flag; nothing was stored.
        assertEquals(ConsentReadResult.Loaded(ConsentDecision.NeverAsked), repository.readState())
    }

    @Test
    fun `setConsent - when write is cancelled - propagates CancellationException`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            repository.setConsent(true)
        }
    }
}
