package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.launcher.core.TimberWrapper

/**
 * Runs [write] against a freshly created destination and, unless it reports success, runs
 * [discard] to remove that destination (§Audit-3 A3-06): a failed or interrupted backup export
 * must not leave a truncated, unimportable `.zip` behind at the user's chosen SAF location.
 *
 * [discard] runs on every non-success path — `false`, a throw, or cancellation — and its own
 * failure (e.g. a provider without delete support) is logged, never masking the original
 * outcome. Exceptions from [write] still propagate to the caller.
 */
internal suspend fun writeOrDiscard(write: suspend () -> Boolean, discard: () -> Unit): Boolean {
    var ok = false
    try {
        ok = write()
        return ok
    } finally {
        if (!ok) {
            runCatching(discard).onFailure { TimberWrapper.silentError(it, "Could not discard failed export target") }
        }
    }
}
