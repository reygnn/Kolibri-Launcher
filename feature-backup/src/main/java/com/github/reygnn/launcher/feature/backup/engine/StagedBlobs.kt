package com.github.reygnn.launcher.feature.backup.engine

import java.io.Closeable
import java.io.File

/**
 * Blobs an import has staged and verified, owned by the caller until [close]
 * (BackupEngineContract): every blob the app applies is [claim]ed — moved to its final
 * place — and everything left over is deleted by [close], also when the import fails or
 * is cancelled. Use it with `use { }`.
 */
class StagedBlobs internal constructor(files: Map<String, File>) : Closeable {

    private val files = LinkedHashMap(files)

    /** Hashes of the blobs that are staged and not yet claimed. */
    val hashes: Set<String> get() = files.keys.toSet()

    /**
     * Moves the staged blob [sha256] to [target] (rename; copy + delete across file
     * systems) and hands ownership to the caller. Null when no such blob is staged — it
     * was rejected, missing or already claimed.
     */
    fun claim(sha256: String, target: File): File? {
        val staged = files.remove(sha256) ?: return null
        target.parentFile?.mkdirs()
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
        return target
    }

    /** Deletes every unclaimed blob. Idempotent. */
    override fun close() {
        files.values.forEach { it.delete() }
        files.clear()
    }
}
