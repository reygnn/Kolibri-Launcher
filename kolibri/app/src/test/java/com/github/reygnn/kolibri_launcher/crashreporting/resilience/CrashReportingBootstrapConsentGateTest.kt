package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the §12·1 (A1) privacy gate of [CrashReportingBootstrap.applyConsentGate]
 * — the wiring that turns the stored [ConsentDecision] into ACRA's initial
 * enabled/disabled state. This is the most privacy-critical sequence in the
 * rewrite and was previously unpinned (no test exercised attachBaseContext at
 * all). Pure JVM via the injected setEnabled/readDecision seams.
 */
class CrashReportingBootstrapConsentGateTest {

    private val calls = mutableListOf<Boolean>()

    private fun gate(decision: ConsentDecision?) =
        CrashReportingBootstrap.applyConsentGate(
            setEnabled = { calls += it },
            readDecision = { decision },
        )

    @Test
    fun `disables first then enables only on Granted`() {
        gate(ConsentDecision.Granted)
        // Deleting the setEnabled(false) call (mutation A) drops the leading
        // false; loosening the == Granted gate (mutation B) would also enable on
        // NeverAsked/Denied below. Either turns a test red.
        assertEquals(listOf(false, true), calls)
    }

    @Test
    fun `stays disabled on NeverAsked`() {
        gate(ConsentDecision.NeverAsked)
        assertEquals(listOf(false), calls)
    }

    @Test
    fun `stays disabled on Denied`() {
        gate(ConsentDecision.Denied)
        assertEquals(listOf(false), calls)
    }

    @Test
    fun `stays disabled in the sender process (null decision)`() {
        gate(null)
        assertEquals(listOf(false), calls)
    }

    @Test
    fun `reads consent only AFTER disabling`() {
        // A1: the disable must precede the consent read, so no window exists where
        // an enabled reporter sees an unknown decision.
        val events = mutableListOf<String>()
        CrashReportingBootstrap.applyConsentGate(
            setEnabled = { events += "setEnabled($it)" },
            readDecision = { events += "read"; ConsentDecision.Granted },
        )
        assertEquals(listOf("setEnabled(false)", "read", "setEnabled(true)"), events)
    }
}
