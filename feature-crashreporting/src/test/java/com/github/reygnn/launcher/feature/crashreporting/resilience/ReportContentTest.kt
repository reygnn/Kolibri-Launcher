package com.github.reygnn.launcher.feature.crashreporting.resilience

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.acra.ReportField
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
        assertThat(CrashReportingBootstrap.REPORT_CONTENT).isEqualTo(listOf(
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.STACK_TRACE,
            ))
    }

    @Test
    fun `reportContent excludes CUSTOM_DATA and LOGCAT`() {
        assertThat(CrashReportingBootstrap.REPORT_CONTENT.size).isEqualTo(7)
        assertWithMessage("CUSTOM_DATA must never be collected — it is the AUDIT-6 #4 race / PII surface (B4/B5)").that(CrashReportingBootstrap.REPORT_CONTENT.contains(ReportField.CUSTOM_DATA)).isFalse()
        assertWithMessage("LOGCAT must never be collected (B5)").that(CrashReportingBootstrap.REPORT_CONTENT.contains(ReportField.LOGCAT)).isFalse()
    }
}
