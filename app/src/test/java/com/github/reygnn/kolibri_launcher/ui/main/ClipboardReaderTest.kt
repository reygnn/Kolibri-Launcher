package com.github.reygnn.kolibri_launcher.ui.main

import android.content.ClipData
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ClipboardReader] — no Android runtime, no Robolectric.
 * The [ClipData] instances are opaque MockK tokens: the reader never calls a
 * method on them itself, it only routes them through the two injected lambdas,
 * so nothing on the token needs stubbing.
 *
 * The load-bearing case is [sensitive clip is refused before any coercion runs]:
 * it pins that a sensitive clip's backing stream is never read.
 */
class ClipboardReaderTest {

    @Test
    fun `null clip resolves to a non-sensitive empty read`() {
        var isSensitiveCalled = false
        var coerceCalled = false

        val read = ClipboardReader.read(
            clip = null,
            isSensitive = { isSensitiveCalled = true; false },
            coerceFirstItem = { coerceCalled = true; "x" },
        )

        assertNull(read.text)
        assertFalse(read.isSensitive)
        // A null clip short-circuits before either Android touch point.
        assertFalse("isSensitive must not run for a null clip", isSensitiveCalled)
        assertFalse("coercion must not run for a null clip", coerceCalled)
    }

    @Test
    fun `sensitive clip is refused before any coercion runs`() {
        var coerceCalled = false

        val read = ClipboardReader.read(
            clip = mockk<ClipData>(),
            isSensitive = { true },
            // If this ever runs, a sensitive URI-backed clip's stream would be
            // read — exactly what the guard exists to prevent.
            coerceFirstItem = { coerceCalled = true; "secret" },
        )

        assertTrue(read.isSensitive)
        assertNull("a sensitive clip yields no text", read.text)
        assertFalse("coercion must never run for a sensitive clip", coerceCalled)
    }

    @Test
    fun `non-sensitive clip forwards the coerced text`() {
        val read = ClipboardReader.read(
            clip = mockk<ClipData>(),
            isSensitive = { false },
            coerceFirstItem = { "https://example.com" },
        )

        assertEquals("https://example.com", read.text)
        assertFalse(read.isSensitive)
    }

    @Test
    fun `non-sensitive clip with no coercible item yields null text`() {
        val read = ClipboardReader.read(
            clip = mockk<ClipData>(),
            isSensitive = { false },
            coerceFirstItem = { null },
        )

        assertNull(read.text)
        assertFalse(read.isSensitive)
    }

    @Test
    fun `coerced CharSequence is normalised to a String`() {
        // coerceToText returns a CharSequence; the reader must hand callers a
        // String so downstream equals/length behave predictably.
        val read = ClipboardReader.read(
            clip = mockk<ClipData>(),
            isSensitive = { false },
            coerceFirstItem = { StringBuilder("built") },
        )

        assertEquals("built", read.text)
        assertTrue(read.text is String)
    }
}
