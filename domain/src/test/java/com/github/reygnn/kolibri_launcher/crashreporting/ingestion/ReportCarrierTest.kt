package com.github.reygnn.kolibri_launcher.crashreporting.ingestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Pins the pure log-context carrier used by AcraTree (B4). No Android, no ACRA
 * — just the transition data.
 */
class ReportCarrierTest {

    @Test
    fun `wraps cause in a LoggedThrowable preserving the original as cause`() {
        val original = IOException("disk gone")
        val result = buildAcraReportThrowable(6, "MyTag", "save failed", original)

        assertTrue("carrier must be a LoggedThrowable", result is LoggedThrowable)
        assertSame("original throwable must be preserved as cause", original, result.cause)
    }

    @Test
    fun `message encodes logcat-style priority label, tag, cause type and message`() {
        val result = buildAcraReportThrowable(6, "MyTag", "save failed", IOException())
        assertEquals("[E/MyTag] IOException: save failed", result.message)
    }

    @Test
    fun `header carries the original exception type for server-side grouping`() {
        // Top-level report type is always LoggedThrowable, so the real type must
        // survive in the message to stay groupable/filterable server-side.
        val result = buildAcraReportThrowable(6, "T", "boom", IllegalStateException("x"))
        assertTrue(
            "message must name the original exception type",
            result.message!!.contains("IllegalStateException"),
        )
    }

    @Test
    fun `null tag falls back to Unknown`() {
        val result = buildAcraReportThrowable(5, null, "hmm", IOException())
        assertEquals("[W/Unknown] IOException: hmm", result.message)
    }

    @Test
    fun `unknown priority falls back to its numeric value`() {
        val result = buildAcraReportThrowable(99, "T", "x", IOException())
        assertEquals("[99/T] IOException: x", result.message)
    }

    @Test
    fun `cancellation cause gets the improper-catch diagnosis note`() {
        val cancellation = CancellationException("job cancelled")
        val result = buildAcraReportThrowable(6, "Scope", "coroutine died", cancellation)

        assertSame("cancellation must be preserved as cause", cancellation, result.cause)
        val msg = result.message
        assertNotNull(msg)
        assertTrue(
            "cancellation reports must carry the diagnostic note",
            msg!!.contains("DIAGNOSIS") && msg.contains("CancellationException"),
        )
    }

    @Test
    fun `non-cancellation cause gets no diagnosis note`() {
        val result = buildAcraReportThrowable(6, "T", "normal error", IOException())
        assertFalse(result.message!!.contains("DIAGNOSIS"))
    }
}
