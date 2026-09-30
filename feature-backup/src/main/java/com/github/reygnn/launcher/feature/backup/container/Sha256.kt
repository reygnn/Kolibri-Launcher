package com.github.reygnn.launcher.feature.backup.container

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

/** Copies [input] to [output], feeding the digest; returns the number of bytes copied. */
internal fun copyHashing(input: InputStream, output: OutputStream?, digest: MessageDigest): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
        output?.write(buffer, 0, n)
        total += n
    }
    return total
}
