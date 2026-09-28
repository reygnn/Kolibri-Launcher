package com.github.reygnn.nyx_launcher.data.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

/** Pure JVM tests for the byte-counting stream used to bound backup extraction. */
class CappedInputStreamTest {

    private fun capped(bytes: ByteArray, limit: Long) =
        CappedInputStream(ByteArrayInputStream(bytes), limit)

    @Test fun reads_a_source_smaller_than_the_limit_in_full() {
        val capped = capped(byteArrayOf(1, 2, 3), limit = 10)
        assertThat(capped.readBytes().toList())
            .containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(capped.limitReached).isFalse()
    }

    @Test fun stops_at_the_limit_and_flags_an_over_limit_source() {
        // budget+1 idiom: 4 source bytes, limit 3 → returns 3 then EOF, and flags the overrun.
        val capped = capped(byteArrayOf(1, 2, 3, 4), limit = 3)
        assertThat(capped.readBytes().size).isEqualTo(3)
        assertThat(capped.limitReached).isTrue()
    }

    @Test fun a_source_one_below_the_limit_is_not_flagged() {
        // budget+1 idiom: a payload of exactly `budget` (=2) bytes, limit budget+1 (=3), passes.
        val capped = capped(byteArrayOf(1, 2), limit = 3)
        assertThat(capped.readBytes().size).isEqualTo(2)
        assertThat(capped.limitReached).isFalse()
    }

    @Test fun single_byte_reads_count_toward_the_limit() {
        val capped = capped(byteArrayOf(9, 9, 9), limit = 2)
        assertThat(capped.read()).isEqualTo(9)
        assertThat(capped.read()).isEqualTo(9)
        assertThat(capped.read()).isEqualTo(-1) // limit hit
        assertThat(capped.limitReached).isTrue()
    }

    @Test fun an_empty_source_never_reaches_the_limit() {
        val capped = capped(ByteArray(0), limit = 5)
        assertThat(capped.read()).isEqualTo(-1)
        assertThat(capped.limitReached).isFalse()
    }
}
