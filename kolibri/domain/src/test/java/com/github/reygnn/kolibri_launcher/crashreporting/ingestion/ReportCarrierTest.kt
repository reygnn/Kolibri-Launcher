package com.github.reygnn.kolibri_launcher.crashreporting.ingestion

import com.github.reygnn.launcher.core.crashreporting.ingestion.LoggedThrowable
import com.github.reygnn.launcher.core.crashreporting.ingestion.buildAcraReportThrowable
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.assertIs
import org.junit.Test

/**
 * Pins the pure log-context carrier used by AcraTree (B4). No Android, no ACRA
 * — just the transition data.
 */
class ReportCarrierTest {

    @Test
    fun `wraps cause in a LoggedThrowable preserving the original as cause`() {
        val original = IOException("disk gone")
        val result = buildAcraReportThrowable(6, "MyTag", "save failed", original)

        assertIs<LoggedThrowable>(result, "carrier must be a LoggedThrowable")
        assertWithMessage("original throwable must be preserved as cause").that(result.cause).isSameInstanceAs(original)
    }

    @Test
    fun `message encodes logcat-style priority label, tag, cause type and message`() {
        val result = buildAcraReportThrowable(6, "MyTag", "save failed", IOException())
        assertThat(result.message).isEqualTo("[E/MyTag] IOException: save failed")
    }

    @Test
    fun `header carries the original exception type for server-side grouping`() {
        // Top-level report type is always LoggedThrowable, so the real type must
        // survive in the message to stay groupable/filterable server-side.
        val result = buildAcraReportThrowable(6, "T", "boom", IllegalStateException("x"))
        assertWithMessage("message must name the original exception type").that(result.message!!.contains("IllegalStateException")).isTrue()
    }

    @Test
    fun `null tag falls back to Unknown`() {
        val result = buildAcraReportThrowable(5, null, "hmm", IOException())
        assertThat(result.message).isEqualTo("[W/Unknown] IOException: hmm")
    }

    @Test
    fun `unknown priority falls back to its numeric value`() {
        val result = buildAcraReportThrowable(99, "T", "x", IOException())
        assertThat(result.message).isEqualTo("[99/T] IOException: x")
    }

    @Test
    fun `cancellation cause gets the improper-catch diagnosis note`() {
        val cancellation = CancellationException("job cancelled")
        val result = buildAcraReportThrowable(6, "Scope", "coroutine died", cancellation)

        assertWithMessage("cancellation must be preserved as cause").that(result.cause).isSameInstanceAs(cancellation)
        val msg = result.message
        assertThat(msg).isNotNull()
        assertWithMessage("cancellation reports must carry the diagnostic note").that(msg!!.contains("DIAGNOSIS") && msg.contains("CancellationException")).isTrue()
    }

    @Test
    fun `non-cancellation cause gets no diagnosis note`() {
        val result = buildAcraReportThrowable(6, "T", "normal error", IOException())
        assertThat(result.message!!.contains("DIAGNOSIS")).isFalse()
    }
}
