package com.github.reygnn.launcher.core

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Caps how many bytes may be read from [source]: once [limit] bytes have been
 * returned it reports EOF (`-1`) instead of reading further, and [limitReached]
 * flips true. A backup import wraps both the whole compressed archive and each
 * decompressed blob in one of these, so a zip / decompression bomb can neither
 * over-read the source stream nor fill the disk. Shared by both launchers'
 * backup engines (nyx + kolibri).
 *
 * Read `limit` as "one past the real budget" (like `readNBytes(budget + 1)`): the
 * caller sets it to `budget + 1` and treats [limitReached] as "the source held more
 * than `budget` bytes → reject", so a payload of exactly `budget` bytes is accepted.
 *
 * Does NOT override [close]; the default [FilterInputStream] behaviour (close the
 * source) is only ever reached by a whole-archive wrapper, whose `ZipInputStream`
 * should close the underlying stream exactly as before. A per-blob wrapper is never
 * closed (the shared `ZipInputStream` must stay open across entries).
 */
class CappedInputStream(
    source: InputStream,
    private val limit: Long,
) : FilterInputStream(source) {
    private var read = 0L

    /** True once [limit] bytes have been returned — i.e. the source had at least that many. */
    val limitReached: Boolean get() = read >= limit

    override fun read(): Int {
        if (read >= limit) return -1
        val b = `in`.read()
        if (b >= 0) read++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= limit) return -1
        val allowed = minOf(len.toLong(), limit - read).toInt()
        val n = `in`.read(b, off, allowed)
        if (n > 0) read += n
        return n
    }
}
