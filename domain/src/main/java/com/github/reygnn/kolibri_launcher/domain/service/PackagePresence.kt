package com.github.reygnn.kolibri_launcher.domain.service

/**
 * Verifies whether a specific launcher target is still present on the device.
 *
 * This is the **deletion gate** for the reconcile rewrite (`RECONCILE_SPEC.md`):
 * a stored assignment (favorite / swipe / hidden / custom-name) is only ever
 * removed when its target is confirmed gone *here*, never because it happens to
 * be missing from a bulk app-list snapshot. That decoupling is what makes a
 * partial or transient list read unable to cause data loss (R-INV).
 *
 * Two resolution levels (`SPEC-DECISION R-1`):
 * - [isComponentPresent] — component-exact (`package/class`), for
 *   favorites / swipe / hidden, so an app that disables *one* launcher alias
 *   (e.g. an icon-hide toggle) is detected.
 * - [isPackagePresent] — package-level, for custom-names (keyed on package).
 *
 * **Fail-safe contract:** any PackageManager failure resolves to `true`
 * ("present"), so a transient system-API error can never delete an assignment.
 *
 * NO CONTRACT TEST (ADR): wraps the Android `PackageManager`, so it is not
 * honestly JVM-instantiable. Covered by `PackagePresenceImplTest` (Robolectric).
 * Same rationale as [ShortcutLauncherService] and the system-API repositories.
 */
interface PackagePresence {

    /**
     * True if `componentName` (`"package/class"`, the flattened launcher-entry
     * form used across the app) still resolves as a launcher activity, or if
     * presence could not be determined (fail-safe). Suspends: the impl hops to
     * IO for the PackageManager query so callers never block the main thread.
     */
    suspend fun isComponentPresent(componentName: String): Boolean

    /**
     * True if `packageName` still has a launcher entry, or if presence could
     * not be determined (fail-safe). Suspends (IO), see [isComponentPresent].
     */
    suspend fun isPackagePresent(packageName: String): Boolean
}
