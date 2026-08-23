package com.github.reygnn.kolibri_launcher.crashreporting.ingestion

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.io.IOException

/**
 * Pins the [AcraTree] gate and swallow (B1/B2/B3/C1) through the REAL production
 * path: plant the tree and log via `Timber`, so dispatch reaches the protected
 * `Tree.log(priority, tag, message, t)` override (a direct `tree.log(...)` call
 * would bind to a different public Timber overload). Delivery goes through the
 * injected `deliver` seam, so the decision is verifiable without ACRA.
 *
 * Runs under Robolectric because Timber's priority levels and AcraTree's `Log.e`
 * swallow need the real `android.util.Log` — the bare-JVM stub reads its
 * constants back as 0 and throws from `Log.e`.
 *
 * Reporting is by INTENT (§23): an entry is delivered iff it carries an intent
 * tag ([TimberWrapper.SILENT_LOG_TAG] or [TimberWrapper.ACRA_REPORT_TAG]) AND a
 * throwable — the log LEVEL is irrelevant. A plain (untagged) `Timber.e/w(t)` is
 * local-only, even at ERROR. Null-throwable entries are always dropped.
 */
@RunWith(RobolectricTestRunner::class)
class AcraTreeTest {

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `SILENT_ERROR-tagged with a throwable delivers exactly one carrier encoding the context`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        Timber.tag(TimberWrapper.SILENT_LOG_TAG).e(IOException("x"), "boom")

        assertEquals(1, delivered.size)
        // Per-report carrier (B4): message folds "[E/SILENT_ERROR] <type>: <msg>".
        // Timber appends the throwable's stack trace to the message before
        // calling log(), so assert the prefix rather than an exact match — the
        // exact carrier format is pinned by ReportCarrierTest.
        assertTrue(delivered.single().message!!.startsWith("[E/SILENT_ERROR] IOException: boom"))
    }

    @Test
    fun `ACRA_REPORT-tagged with a throwable delivers`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        Timber.tag(TimberWrapper.ACRA_REPORT_TAG).e(IOException("x"), "infra boom")

        assertEquals(1, delivered.size)
        assertTrue(delivered.single().message!!.startsWith("[E/ACRA_REPORT] IOException: infra boom"))
    }

    @Test
    fun `intent tag is delivered regardless of level - WARN still reports`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        // The gate is intent, not level: a WARN carrying an intent tag delivers.
        Timber.tag(TimberWrapper.ACRA_REPORT_TAG).w(IOException("x"), "warn but intended")

        assertEquals(1, delivered.size)
        assertTrue(delivered.single().message!!.startsWith("[W/ACRA_REPORT] IOException: warn but intended"))
    }

    @Test
    fun `untagged ERROR with a throwable is NOT delivered - report by intent, not level`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        // The §23 regression guard: a plain Timber.e(t) at ERROR must stay local.
        Timber.tag("SomeClass").e(IOException("x"), "plain error")

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `intent-tagged null throwable is not delivered`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        Timber.tag(TimberWrapper.SILENT_LOG_TAG).e("no throwable")

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a throwing deliver is swallowed and does not propagate`() {
        var delivered = 0
        Timber.plant(AcraTree(deliver = {
            delivered++
            throw RuntimeException("acra is down")
        }))

        // Must not throw (C1); the swallow's Log.e runs on the real runtime.
        Timber.tag(TimberWrapper.ACRA_REPORT_TAG).e(IOException("x"), "boom")

        assertEquals(1, delivered)
    }
}
