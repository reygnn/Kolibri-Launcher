package com.github.reygnn.launcher.feature.backup.engine

import com.github.reygnn.launcher.core.KolibriLog
import java.io.OutputStream

/**
 * U3: an export writes into a document the user has just created. Unless the write
 * completes — [open] succeeds, [write] returns and the stream closes cleanly — the
 * document is removed with [discard], so no half, unimportable backup is left behind.
 *
 * [discard] runs on every other path: [open] throwing, [write] throwing, a failing
 * close/flush, and cancellation. Its own failure (a provider without delete support) is
 * logged and never masks the original exception, which always propagates. Both lambdas
 * are the platform part and stay with the app (e.g. `ContentResolver.openOutputStream`,
 * `DocumentsContract.deleteDocument`); this function is plain JVM (U1).
 */
suspend fun writeOrDiscard(
    open: () -> OutputStream,
    discard: () -> Unit,
    write: suspend (OutputStream) -> Unit,
) {
    var complete = false
    try {
        open().use { write(it) }
        complete = true
    } finally {
        if (!complete) discardQuietly(discard)
    }
}

private fun discardQuietly(discard: () -> Unit) {
    try {
        discard()
    } catch (e: Exception) {
        // no suspension point; Exception sufficient — a best-effort delete of the incomplete
        // document; the export's own failure is what the caller reports.
        KolibriLog.w(e, "Could not delete the incomplete backup document")
    }
}
