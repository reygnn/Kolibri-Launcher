package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.launcher.core.AppPresence
import com.github.reygnn.launcher.core.ComponentKey

/**
 * Test double for the shared [AppPresence] (formerly Kolibri's `FakePackagePresence`).
 * Presence is explicitly controlled via [presentComponents] / [presentPackages]; anything
 * not listed is reported absent.
 *
 * The default (both sets empty → everything absent) matches the pre-veto reconcile
 * behaviour, so orphan-removal tests keep passing unchanged. A veto test sets a specific
 * target present to assert it survives a partial load.
 *
 * Note the component grain is keyed on [ComponentKey] now (the shared API), not the
 * flattened string the old fake used — construct entries with `ComponentKey.parse("pkg/cls")`.
 */
class FakeAppPresence : AppPresence {

    var presentComponents: Set<ComponentKey> = emptySet()
    var presentPackages: Set<String> = emptySet()

    override suspend fun isComponentPresent(key: ComponentKey): Boolean =
        key in presentComponents

    override suspend fun isPackagePresent(packageName: String): Boolean =
        packageName in presentPackages
}
