package com.github.reygnn.launcher.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Polls [condition] every [intervalMs] until it returns true OR until
 * [timeoutMs] elapses. Returns the elapsed milliseconds at first success;
 * throws [AssertionError] on timeout with the [describe] snapshot inline.
 *
 * Product-neutral home of the primitive formerly in
 * `kolibri_launcher.support.awaitUntil` (kept there now as a deprecated
 * forwarder). Public so both apps' androidTest sets and [BasePage] can use it.
 *
 * Why this over Espresso idle: Espresso waits on View / Animation / AsyncTask
 * idle, NOT on Flow / StateFlow emission — a Recycler fed by a StateFlow can
 * still be empty when an Espresso action fires. Why not Thread.sleep: this
 * returns on the first satisfied poll (fast path ~0 ms) while staying bounded.
 * Why not awaitility: ~20 lines beats one more dependency.
 */
public fun awaitUntil(
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
