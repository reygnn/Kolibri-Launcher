package com.github.reygnn.kolibri_launcher.crashreporting.health

import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentReadResult
import com.github.reygnn.kolibri_launcher.crashreporting.consent.CrashReportConsentRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The consent × bootstrap-health decision matrix. The bootstrap-health flag is a
 * seam (`isBootstrapHealthy`), so this pins the CORE insight: a Granted user with a
 * failed bootstrap gate is [CrashReportingHealthState.BROKEN] — the case the naive
 * "is ACRA enabled now" check would miss because the MainActivity reaffirm net
 * masks it.
 */
class CrashReportingHealthMonitorTest {

    private val repository = mockk<CrashReportConsentRepository>()
    private val monitor = CrashReportingHealthMonitor(repository)

    private fun consent(result: ConsentReadResult) {
        coEvery { repository.readState() } returns result
    }

    @Test
    fun `granted and bootstrap healthy is HEALTHY`() = runTest {
        consent(ConsentReadResult.Loaded(ConsentDecision.Granted))
        assertThat(monitor.evaluate(isBootstrapHealthy = { true }))
            .isEqualTo(CrashReportingHealthState.HEALTHY)
    }

    @Test
    fun `granted but bootstrap NOT healthy is BROKEN`() = runTest {
        consent(ConsentReadResult.Loaded(ConsentDecision.Granted))
        assertThat(monitor.evaluate(isBootstrapHealthy = { false }))
            .isEqualTo(CrashReportingHealthState.BROKEN)
    }

    @Test
    fun `denied is NOT_APPLICABLE even if bootstrap unhealthy`() = runTest {
        consent(ConsentReadResult.Loaded(ConsentDecision.Denied))
        assertThat(monitor.evaluate(isBootstrapHealthy = { false }))
            .isEqualTo(CrashReportingHealthState.NOT_APPLICABLE)
    }

    @Test
    fun `never asked is NOT_APPLICABLE`() = runTest {
        consent(ConsentReadResult.Loaded(ConsentDecision.NeverAsked))
        assertThat(monitor.evaluate(isBootstrapHealthy = { true }))
            .isEqualTo(CrashReportingHealthState.NOT_APPLICABLE)
    }

    @Test
    fun `unreadable consent is UNKNOWN`() = runTest {
        consent(ConsentReadResult.Unavailable(RuntimeException("io")))
        assertThat(monitor.evaluate(isBootstrapHealthy = { true }))
            .isEqualTo(CrashReportingHealthState.UNKNOWN)
    }
}
