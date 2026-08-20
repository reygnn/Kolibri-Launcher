package com.github.reygnn.kolibri_launcher.core

/**
 * Declares the **settings**-DataStore keys a component actively owns (reads/writes).
 *
 * This is the keep-list that drives storage cleanup
 * ([com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository]). The
 * cleanup is a **blacklist-of-unknown**: it deletes every settings-store key that no live owner
 * claims. Inverting the old whitelist-of-dead means the failure mode inverts too — a key an owner
 * forgets to declare here is not a harmless leftover, it is **live data the cleanup will delete**.
 * That risk is contained by two things, and only by them:
 *  - each owner returns its keys straight from the live `Preferences.Key` objects it already
 *    declares (see the impls) — the key object is the single source, so there is nothing to keep in
 *    sync by hand; and
 *  - `./gradlew checkConventions` enforces, per owner file, that every declared key appears here,
 *    and that no *un-owned* file declares a settings-store key (the "new AnrReporter straggler"
 *    guard). See `tools/check-settings-keys-registered.awk`.
 *
 * Every implementation is aggregated into `Set<OwnsSettingsStoreKeys>` via Hilt multibinding
 * (`@IntoSet`), so registering is automatic — an owner that binds itself is in the keep-list with
 * nothing to add at the aggregation site.
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
