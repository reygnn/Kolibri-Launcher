package com.github.reygnn.kolibri_launcher.crashreporting.consent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Pins the R1 bootstrap consent gate [ConsentBootstrap.readDecision] — the
 * pre-Hilt read that decides whether ACRA starts enabled at process launch. It
 * is deliberately fail-closed and distinct from R2 (the repository's readState,
 * which returns Unavailable): here an unknown token AND any I/O failure both map
 * to NeverAsked, so ACRA stays OFF; only a genuine GRANTED enables it (A1), and
 * cancellation still propagates (A6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentBootstrapTest {

    private val store = FakeDataStore()

    @Test
    fun `GRANTED token maps to Granted`() = runTest {
        store.setInitialData(preferencesOf(ConsentBootstrap.CONSENT_DECISION_KEY to ConsentBootstrap.VALUE_GRANTED))

        assertEquals(ConsentDecision.Granted, ConsentBootstrap.readDecision(store))
    }

    @Test
    fun `DENIED token maps to Denied`() = runTest {
        store.setInitialData(preferencesOf(ConsentBootstrap.CONSENT_DECISION_KEY to ConsentBootstrap.VALUE_DENIED))

        assertEquals(ConsentDecision.Denied, ConsentBootstrap.readDecision(store))
    }

    @Test
    fun `an absent key maps to NeverAsked`() = runTest {
        assertEquals(ConsentDecision.NeverAsked, ConsentBootstrap.readDecision(store))
    }

    @Test
    fun `an unknown token maps to NeverAsked (fail-closed, not Unavailable at R1)`() = runTest {
        store.setInitialData(preferencesOf(ConsentBootstrap.CONSENT_DECISION_KEY to "BOGUS"))

        assertEquals(ConsentDecision.NeverAsked, ConsentBootstrap.readDecision(store))
    }

    @Test
    fun `a read failure maps to NeverAsked (fail-closed)`() = runTest {
        store.makeReadFail()

        assertEquals(ConsentDecision.NeverAsked, ConsentBootstrap.readDecision(store))
    }

    @Test
    fun `a cancelled read propagates CancellationException`() = runTest {
        // The CancellationException catch must be ordered BEFORE the generic
        // Exception -> NeverAsked fallback, or cancellation would be swallowed.
        val cancellingStore = mockk<DataStore<Preferences>>()
        every { cancellingStore.data } returns flow { throw CancellationException("simulated read cancellation") }

        assertFailsWith<CancellationException> {
            ConsentBootstrap.readDecision(cancellingStore)
        }
    }
}
