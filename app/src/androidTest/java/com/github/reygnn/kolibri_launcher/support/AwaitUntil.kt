package com.github.reygnn.kolibri_launcher.support

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Polls [condition] every [intervalMs] until it returns true OR until
 * [timeoutMs] elapses. Returns the elapsed milliseconds when the condition
 * was first satisfied; throws [AssertionError] on timeout with the
 * [describe] snapshot in the failure message so the cause is visible.
 *
 * Usage in instrumented tests:
 * ```
 * val convergedMs = awaitUntil(
 *     timeoutMs = 5_000,
 *     describe = { "viaRoleManager=${roleManager.isRoleHeld(ROLE_HOME)}, viaPM=${pmReports()}" },
 * ) {
 *     !roleManager.isRoleHeld(ROLE_HOME) && !pmReports()
 * }
 * assertThat(convergedMs).isAtMost(500)
 * ```
 *
 * Why this and not Thread.sleep: Thread.sleep gives a fixed wait regardless
 * of how fast the condition becomes true; this returns immediately on the
 * first satisfied poll, so a fast cache refresh costs ~0 ms while a slow
 * one is still bounded. Why this and not awaitility: avoids one more
 * dependency for ~20 lines of code.
 *
 * Failure semantics: timeout is a real failure, not a flake — the failure
 * message includes the latest observed state to make the diagnosis fall
 * out of the assertion alone (otherwise reading logcat is the only way to
 * learn what went wrong, and our infra strips logcat after each test).
 */
internal fun awaitUntil(
    timeoutMs: Long = 5_000,
    intervalMs: Long = 50,
    describe: () -> String = { "<no describe lambda provided>" },
    condition: () -> Boolean,
): Long = runBlocking {
    val start = System.nanoTime()
    val result = withTimeoutOrNull(timeoutMs) {
        while (!condition()) {
            delay(intervalMs)
        }
        (System.nanoTime() - start) / 1_000_000
    }
    result ?: run {
        val elapsed = (System.nanoTime() - start) / 1_000_000
        throw AssertionError(
            "awaitUntil timed out after ${elapsed}ms (budget=${timeoutMs}ms). " +
                "Last observed state: ${describe()}"
        )
    }
}
