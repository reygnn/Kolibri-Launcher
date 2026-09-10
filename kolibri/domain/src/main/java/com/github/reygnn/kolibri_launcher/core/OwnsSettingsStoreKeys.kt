package com.github.reygnn.kolibri_launcher.core

/**
 * Declares the **settings**-DataStore keys a component actively owns (reads/writes).
 *
 * This is the keep-list that drives storage cleanup
 * ([com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository]). The
 * cleanup is a **blacklist-of-unknown**: it deletes every settings-store key that no live owner
 * claims. Inverting the old whitelist-of-dead means the failure mode inverts too — a key an owner
 * forgets to declare here is not a harmless leftover, it is **live data the cleanup will delete**.
 * That risk is contained by three things, and only by them:
 *  - each owner returns its keys straight from the live `Preferences.Key` objects it already
 *    declares (see the impls) — the key object is the single source, so there is nothing to keep in
 *    sync by hand; and
 *  - `./gradlew checkConventions` enforces three gates: per owner file, that every declared key
 *    appears here (completeness); that no *un-owned* file declares a settings-store key (the "new
 *    AnrReporter straggler" guard); and that every owner is `@IntoSet`-bound (binding parity, below).
 *    See `tools/check-settings-keys-registered.awk` and `tools/check-conventions.sh`.
 *
 * Every implementation is aggregated into `Set<OwnsSettingsStoreKeys>` via Hilt multibinding. Note
 * that `@IntoSet` registration is **NOT** automatic: implementing this interface has no Dagger
 * effect on its own — you must add a `@Binds/@Provides @IntoSet` line (RepositoryModule for `:data`,
 * SettingsStoreKeyOwnerModule for `:app`). Forgetting it would silently drop the owner from the set
 * and let the cleanup delete its live keys, so the binding-parity gate above fails the build unless
 * every owner is bound.
 *
 * Scope is the **settings** store only. Owners of the consent store (privacy) or the usage store
 * (self-healing live data) do NOT implement this — their keys are out of the cleanup's reach by
 * construction, not by being listed.
 */
interface OwnsSettingsStoreKeys {

    /** Exact settings-store key names this component owns. */
    fun ownedExactKeys(): Set<String> = emptySet()

    /**
     * Settings-store key-name PREFIXES this component owns (dynamic per-entity keys, e.g. the
     * `name_<pkg>` custom-name keys built from [AppConstants.KEY_NAME_PREFIX]).
     */
    fun ownedKeyPrefixes(): Set<String> = emptySet()
}
