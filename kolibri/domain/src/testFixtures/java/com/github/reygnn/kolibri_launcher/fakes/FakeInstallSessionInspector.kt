package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.launcher.core.InstallSessionInspector

/**
 * Test double for the shared [InstallSessionInspector] — the second arm of the store-reconcile
 * deletion gate (AUDIT-1 F7 review point 5). [active] is the set of package names with an
 * install/restore session in flight; the default (empty) means "nothing being restored", so the
 * existing orphan-removal tests keep their outcome unchanged. Set [undetermined] to simulate a
 * failed session query — [activeSessionPackages] then returns `null`, and the caller must
 * fail-safe keep every candidate.
 *
 * [reads] counts how often the set was fetched, so a test can pin the batching cost: it is 0 on
 * the present/complete paths (no candidate ever reaches the session arm) and 1 across a whole pass
 * when some candidate is absent (read once per pass, shared over all four stores — never per key).
 */
class FakeInstallSessionInspector(
    var active: Set<String> = emptySet(),
    var undetermined: Boolean = false,
) : InstallSessionInspector {

    var reads = 0
        private set

    override suspend fun activeSessionPackages(): Set<String>? {
        reads++
        return if (undetermined) null else active
    }
}
