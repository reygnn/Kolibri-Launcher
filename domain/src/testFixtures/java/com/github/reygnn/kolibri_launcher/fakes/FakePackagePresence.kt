package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.service.PackagePresence

/**
 * Test double for [PackagePresence]. Presence is explicitly controlled via
 * [presentComponents] / [presentPackages]; anything not listed is reported
 * absent.
 *
 * The default (both sets empty → everything absent) matches the pre-veto
 * reconcile behaviour, so orphan-removal tests keep passing unchanged. A veto
 * test sets a specific target present to assert it survives a partial load.
 */
class FakePackagePresence : PackagePresence {

    var presentComponents: Set<String> = emptySet()
    var presentPackages: Set<String> = emptySet()

    override suspend fun isComponentPresent(componentName: String): Boolean =
        componentName in presentComponents

    override suspend fun isPackagePresent(packageName: String): Boolean =
        packageName in presentPackages
}
