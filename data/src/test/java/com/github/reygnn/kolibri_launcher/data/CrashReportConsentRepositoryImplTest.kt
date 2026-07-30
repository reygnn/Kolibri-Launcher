package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

/**
 * Impl-specific SAD-PATH tests for [CrashReportConsentRepositoryImpl].
 *
 * The happy-path behaviour (set/get roundtrips) is pinned by
 * [CrashReportConsentRepositoryImplContractTest]. This file covers the
 * error envelope the contract deliberately leaves out (see the contract's
 * "NOT IN CONTRACT" note):
 *   - a failed DataStore read falls back to `false` (never throws), so a
 *     corrupt/unreadable store keeps ACRA off (privacy-safe default);
 *   - a failed write is swallowed (best-effort persistence);
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
    fun `readState - when read fails - falls back to all-false state`() = runTest {
        fakeDataStore.makeReadFail()

        val state = repository.readState()

        assertFalse(state.hasConsent)
        assertFalse(state.hasAsked)
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
    fun `setConsent - when edit fails - does not crash and persists nothing`() = runTest {
        fakeDataStore.makeEditFail()

        // Must not throw despite the underlying edit failing.
        repository.setConsent(true)

        // Read path is unaffected by the edit-fail flag; nothing was stored.
        assertFalse(repository.hasConsent())
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
