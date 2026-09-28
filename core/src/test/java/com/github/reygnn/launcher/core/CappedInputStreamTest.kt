package com.github.reygnn.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Pure JVM tests for the byte-counting stream used to bound backup extraction. */
class CappedInputStreamTest {

    private fun capped(bytes: ByteArray, limit: Long) =
        CappedInputStream(ByteArrayInputStream(bytes), limit)

    @Test fun reads_a_source_smaller_than_the_limit_in_full() {
        val capped = capped(byteArrayOf(1, 2, 3), limit = 10)
        assertEquals(listOf<Byte>(1, 2, 3), capped.readBytes().toList())
        assertFalse(capped.limitReached)
    }

    @Test fun stops_at_the_limit_and_flags_an_over_limit_source() {
        // budget+1 idiom: 4 source bytes, limit 3 → returns 3 then EOF, and flags the overrun.
        val capped = capped(byteArrayOf(1, 2, 3, 4), limit = 3)
        assertEquals(3, capped.readBytes().size)
        assertTrue(capped.limitReached)
    }

    @Test fun a_source_one_below_the_limit_is_not_flagged() {
        // budget+1 idiom: a payload of exactly `budget` (=2) bytes, limit budget+1 (=3), passes.
        val capped = capped(byteArrayOf(1, 2), limit = 3)
        assertEquals(2, capped.readBytes().size)
        assertFalse(capped.limitReached)
    }

    @Test fun single_byte_reads_count_toward_the_limit() {
        val capped = capped(byteArrayOf(9, 9, 9), limit = 2)
        assertEquals(9, capped.read())
        assertEquals(9, capped.read())
        assertEquals(-1, capped.read()) // limit hit
        assertTrue(capped.limitReached)
    }

    @Test fun an_empty_source_never_reaches_the_limit() {
        val capped = capped(ByteArray(0), limit = 5)
        assertEquals(-1, capped.read())
        assertFalse(capped.limitReached)
    }
}
