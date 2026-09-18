package com.github.reygnn.nyx_launcher.home.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped holder for the set of package names that currently have a dot-worthy
 * notification — presence ONLY, no titles/text/counts (privacy). Written by
 * [NyxNotificationListenerService], read as a [StateFlow] by the UI. [Singleton] so the
 * service and the ViewModel share one instance; it survives Activity re-creation, so a
 * returning home immediately reflects the current dots.
 *
 * Runtime state, not persistence — hence a plain app-scoped store rather than a
 * repository. Seeded empty; [clear]ed when the listener disconnects (access revoked).
 */
@Singleton
class NotificationPresenceStore @Inject constructor() {
    private val _packages = MutableStateFlow<Set<String>>(emptySet())
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    /** Replace the dot-package set (called by the listener service on any change). */
    fun update(dotPackages: Set<String>) {
        _packages.value = dotPackages
    }

    /** Drop all dots (e.g. listener disconnected / access revoked). */
    fun clear() {
        _packages.value = emptySet()
    }
}
