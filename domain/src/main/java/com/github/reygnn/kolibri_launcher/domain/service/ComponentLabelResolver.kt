package com.github.reygnn.kolibri_launcher.domain.service

/**
 * Resolves the launcher label of a SINGLE component on demand, without the full
 * app enumeration.
 *
 * This is the first-paint accelerator for the home-screen favorites
 * (`GetFavoriteAppsUseCase`): on a cold start the authoritative favorites list is
 * gated by the whole PackageManager enumeration (queryIntentActivities + one
 * `loadLabel` IPC per installed app, ~150 ms). Favorites are pure text buttons, so
 * the only thing needed to paint one provisionally is its label — and there are at
 * most `MAX_FAVORITES_ON_HOME` of them. Resolving just those handful of labels
 * directly (one scoped query + loadLabel each) is an order of magnitude cheaper than
 * the bulk enumeration, so the favorites can paint almost immediately and the
 * authoritative pass then replaces them in place.
 *
 * **Fail-closed contract (opposite of [PackagePresence]):** a component that no
 * longer resolves as a launcher activity — or any PackageManager failure — returns
 * `null`, i.e. that favorite is simply OMITTED from the provisional paint (it fills
 * in when the authoritative enumeration completes). This is the inverse of
 * [PackagePresence]'s fail-to-`true`, because the consequences are inverted: there a
 * false-negative would DELETE a stored assignment, so it must fail safe toward
 * "present"; here a false-positive would paint a GHOST favorite for an app that is
 * gone, so it must fail toward "omit". Under-showing for one frame is always safe;
 * showing a ghost or a wrong label is not.
 *
 * NO CONTRACT TEST (ADR): wraps the Android `PackageManager`, so it is not honestly
 * JVM-instantiable. Covered by `ComponentLabelResolverImplTest` (Robolectric). Same
 * rationale as [PackagePresence] and the system-API repositories.
 */
interface ComponentLabelResolver {

    /**
     * The current launcher label of `componentName` (`"package/class"`, the
     * flattened launcher-entry form used across the app), or `null` if the component
     * does not currently resolve as a launcher activity or its label could not be
     * determined (fail-closed, see the interface KDoc). A blank label falls back to
     * the package name, mirroring the bulk enumeration in
     * `InstalledAppsRepositoryImpl`. Suspends: the impl hops to IO for the
     * PackageManager query so callers never block the main thread.
     */
    suspend fun resolveLabel(componentName: String): String?
}
