package com.github.reygnn.nyx_launcher.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Pure JVM tests for [writeOrDiscard] (§Audit-3 A3-06): a failed backup export removes its
 * half-written SAF target; a successful one keeps it.
 */
class WriteOrDiscardTest {

    private var discards = 0

    @Test fun success_keeps_the_target() = runTest {
        assertThat(writeOrDiscard(write = { true }, discard = { discards++ })).isTrue()
        assertThat(discards).isEqualTo(0)
    }

    @Test fun a_reported_failure_discards_the_target() = runTest {
        assertThat(writeOrDiscard(write = { false }, discard = { discards++ })).isFalse()
        assertThat(discards).isEqualTo(1)
    }

    @Test fun a_throwing_write_discards_and_still_propagates() = runTest {
        val result = runCatching { writeOrDiscard(write = { throw IOException("SAF gone") }, discard = { discards++ }) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(discards).isEqualTo(1)
    }

    @Test fun cancellation_discards_and_still_propagates() = runTest {
        val result = runCatching { writeOrDiscard(write = { throw CancellationException() }, discard = { discards++ }) }
        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(discards).isEqualTo(1)
    }

    @Test fun a_failing_discard_does_not_mask_the_failure_result() = runTest {
        assertThat(writeOrDiscard(write = { false }, discard = { throw UnsupportedOperationException("no delete") })).isFalse()
    }
}
