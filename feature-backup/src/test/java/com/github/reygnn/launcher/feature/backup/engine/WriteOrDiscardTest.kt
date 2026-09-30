package com.github.reygnn.launcher.feature.backup.engine

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertFailsWith

/**
 * U3 (BackupEngineContract, "a half export is deleted"): a failed or cancelled export
 * removes its document, a complete one keeps it, and the original failure always wins
 * over a failing delete.
 */
class WriteOrDiscardTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private var discards = 0
    private val discard: () -> Unit = { discards++ }

    /** Records whether it was closed; optionally fails on close like a full disk on flush. */
    private class TrackingStream(private val failOnClose: Boolean = false) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closed = false
        override fun write(b: Int) = bytes.write(b)
        override fun close() {
            closed = true
            if (failOnClose) throw IOException("flush failed")
        }
    }

    @Test
    fun `a complete write keeps the document and closes the stream`() = runTest(mainDispatcherRule.testDispatcher) {
        val stream = TrackingStream()
        writeOrDiscard(open = { stream }, discard = discard) { it.write(42) }
        assertThat(discards).isEqualTo(0)
        assertThat(stream.closed).isTrue()
        assertThat(stream.bytes.toByteArray()).isEqualTo(byteArrayOf(42))
    }

    @Test
    fun `a throwing write discards and propagates`() = runTest(mainDispatcherRule.testDispatcher) {
        val stream = TrackingStream()
        assertFailsWith<IOException> {
            writeOrDiscard(open = { stream }, discard = discard) { throw IOException("SAF gone") }
        }
        assertThat(discards).isEqualTo(1)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `a failing open discards and propagates`() = runTest(mainDispatcherRule.testDispatcher) {
        assertFailsWith<IOException> {
            writeOrDiscard(open = { throw IOException("cannot open") }, discard = discard) { }
        }
        assertThat(discards).isEqualTo(1)
    }

    @Test
    fun `a failing close counts as an incomplete write`() = runTest(mainDispatcherRule.testDispatcher) {
        assertFailsWith<IOException> {
            writeOrDiscard(open = { TrackingStream(failOnClose = true) }, discard = discard) { it.write(1) }
        }
        assertThat(discards).isEqualTo(1)
    }

    @Test
    fun `cancellation discards and propagates`() = runTest(mainDispatcherRule.testDispatcher) {
        assertFailsWith<CancellationException> {
            writeOrDiscard(open = { TrackingStream() }, discard = discard) { throw CancellationException("left the screen") }
        }
        assertThat(discards).isEqualTo(1)
    }

    @Test
    fun `a failing discard does not mask the original failure`() = runTest(mainDispatcherRule.testDispatcher) {
        val error = assertFailsWith<IOException> {
            writeOrDiscard(
                open = { TrackingStream() },
                discard = { throw UnsupportedOperationException("no delete support") },
            ) { throw IOException("SAF gone") }
        }
        assertThat(error).hasMessageThat().isEqualTo("SAF gone")
    }
}
