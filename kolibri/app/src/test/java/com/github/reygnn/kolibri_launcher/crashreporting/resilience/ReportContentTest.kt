package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import org.acra.ReportField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the ACRA `reportContent` field list (B5) — the report's data-
 * minimization boundary. A future PII-bearing addition (a device identifier,
 * Logcat) or a re-introduction of `CUSTOM_DATA` (the AUDIT-6 #4 thread-race /
 * PII surface the ReportCarrier design exists to avoid, B4) is the exact leak
 * B5 forbids, and would break this test.
 */
class ReportContentTest {

    @Test
    fun `reportContent is exactly the seven minimal fields, in order`() {
        assertEquals(
            listOf(
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.STACK_TRACE,
            ),
            CrashReportingBootstrap.REPORT_CONTENT,
        )
    }

    @Test
    fun `reportContent excludes CUSTOM_DATA and LOGCAT`() {
        assertEquals(7, CrashReportingBootstrap.REPORT_CONTENT.size)
        assertFalse(
            "CUSTOM_DATA must never be collected — it is the AUDIT-6 #4 race / PII surface (B4/B5)",
            CrashReportingBootstrap.REPORT_CONTENT.contains(ReportField.CUSTOM_DATA),
        )
        assertFalse(
            "LOGCAT must never be collected (B5)",
            CrashReportingBootstrap.REPORT_CONTENT.contains(ReportField.LOGCAT),
        )
    }
}
