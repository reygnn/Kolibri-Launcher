package com.github.reygnn.kolibri_launcher.support

import com.github.reygnn.launcher.testing.awaitUntil as sharedAwaitUntil

/**
 * MOVED to :common-testing-android (com.github.reygnn.launcher.testing.awaitUntil)
 * so both apps and BasePage share one definition. This forwarder keeps the
 * existing ~12 call sites compiling; migrate their imports to the shared
 * package opportunistically, then delete this file.
 */
@Deprecated(
    message = "Use com.github.reygnn.launcher.testing.awaitUntil",
    replaceWith = ReplaceWith(
        "awaitUntil(timeoutMs, intervalMs, describe, condition)",
        "com.github.reygnn.launcher.testing.awaitUntil",
    ),
)
internal fun awaitUntil(
    timeoutMs: Long = 5_000,
    intervalMs: Long = 50,
    describe: () -> String = { "<no describe lambda provided>" },
    condition: () -> Boolean,
): Long = sharedAwaitUntil(timeoutMs, intervalMs, describe, condition)
