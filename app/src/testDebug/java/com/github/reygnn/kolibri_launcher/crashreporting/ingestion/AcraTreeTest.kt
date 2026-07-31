package com.github.reygnn.kolibri_launcher.crashreporting.ingestion

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
 * Runs under Robolectric because both Timber's priority levels and AcraTree's
 * `Log.WARN` gate / `Log.e` swallow need the real `android.util.Log` — the
 * bare-JVM stub reads its constants back as 0 and throws from `Log.e`.
 *
 *  - Gate: only WARN+ WITH a throwable reaches `deliver`; below WARN and
 *    null-throwable are dropped.
 *  - The delivered carrier folds the log context (B2/B4).
 *  - Swallow: a throwing `deliver` does not propagate (C1).
 */
@RunWith(RobolectricTestRunner::class)
class AcraTreeTest {

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `WARN with a throwable delivers exactly one carrier encoding the context`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        Timber.tag("Tag").w(IOException("x"), "boom")

        assertEquals(1, delivered.size)
        // Per-report carrier (B4): message folds "[W/Tag] <type>: <msg>".
        // Timber appends the throwable's stack trace to the message before
        // calling log(), so assert the prefix rather than an exact match — the
        // exact carrier format is pinned by ReportCarrierTest.
        assertTrue(delivered.single().message!!.startsWith("[W/Tag] IOException: boom"))
    }

    @Test
    fun `below WARN is not delivered`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        Timber.tag("Tag").i(IOException("x"), "just info")

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `null throwable is not delivered`() {
        val delivered = mutableListOf<Throwable>()
        Timber.plant(AcraTree(deliver = { delivered += it }))

        Timber.tag("Tag").e("no throwable")

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
        Timber.tag("Tag").e(IOException("x"), "boom")

        assertEquals(1, delivered)
    }
}
