package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.Purgeable
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import kotlinx.coroutines.flow.Flow

/**
 * Records app-launch timestamps and scores apps by time-weighted usage, so the drawer can
 * order loose apps by "most used" (mirrors kolibri's AppUsageRepository, over Nyx's
 * [LauncherApp]/[ComponentKey] model). Usage is aggregated per PACKAGE.
 *
 * The scoring math is the shared, battle-tested `:core`
 * [com.github.reygnn.launcher.core.timeWeightedUsageScore] +
 * [com.github.reygnn.launcher.core.isValidUsageTimestamp]; this repository only owns the
 * (separate) DataStore persistence and the reactive snapshot.
 *
 * Contract + triple: `AppUsageRepositoryContract` (abstract),
 * `FakeAppUsageRepositoryContractTest`, `AppUsageRepositoryImplContractTest` (Rule 2).
 */
interface AppUsageRepository : Purgeable {
    /**
     * Reactive usage snapshot: emits once initially and again whenever the stored usage
     * changes, carrying already-PARSED timestamps (package name → launch epochs, millis). A
     * consumer re-sorts by feeding the emitted map to [scoreApps] — so the store is read and
     * parsed ONCE per real change, not per re-sort. A launch ticks this flow, which the
     * drawer projection uses to re-order reactively.
     */
    val usageSnapshotFlow: Flow<Map<String, List<Long>>>

    /** Record a launch for [packageName] at the current time (blank/null = no-op). */
    suspend fun recordPackageLaunch(packageName: String?)

    /**
     * PURE scoring of [apps] against an already-parsed [usageSnapshot] (from
     * [usageSnapshotFlow]): each app's [ComponentKey] → its time-weighted usage score (higher
     * = more/recently used, 0.0 when unused). Does not read the store. Timestamps are
     * validity-filtered against the current time (scoring is time-dependent).
     */
    fun scoreApps(apps: List<LauncherApp>, usageSnapshot: Map<String, List<Long>>): Map<ComponentKey, Double>
}
