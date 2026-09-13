package com.github.reygnn.kolibri_launcher.domain.repository

/**
 * Resolves the user's current default apps (dialer, SMS, email, browser, camera) to
 * their package names — used to pre-select sensible favorites during first-run
 * onboarding so the home screen isn't empty to start with.
 *
 * System-API backed (the impl reads `TelecomManager` / `Telephony` /
 * `PackageManager`), which is why it hides behind this interface: tests swap the
 * real implementation for a double.
 */
interface DefaultAppsRepository {
    /**
     * Package names of the user's current default phone, SMS, email, browser and
     * camera apps, in that order. Any role that is unset or unresolvable is omitted, and
     * duplicates / the caller's own package / the system resolver package are
     * dropped — so the result is a (possibly empty) list of distinct, real default
     * app packages, never a placeholder.
     */
    suspend fun getDefaultAppPackages(): List<String>
}
