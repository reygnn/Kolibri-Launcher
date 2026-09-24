package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import kotlinx.coroutines.flow.Flow

/**
 * The single data-access seam for the home layout (CLAUDE.md rule 1).
 *
 * [layout] is a cold [Flow] — one authoritative read path, no hot-share
 * parameter (mirrors the big Kolibri's DATASTORE_READ_SPEC posture). The layout
 * is persisted as one versioned blob (ICON_HOME_MODEL_SPEC §7-E1).
 *
 * Transition-based mutations go through [update] — an ATOMIC read-modify-write,
 * so a background reconcile and a user edit can't both read the same version and
 * clobber each other (AUDIT-1 A1-03). [save] is a serialized full replace
 * (import / seed).
 *
 * Contract + triple: `HomeLayoutRepositoryContract` (abstract),
 * `FakeHomeLayoutRepositoryContractTest`, and — once `:data` lands —
 * `HomeLayoutRepositoryImplContractTest` (CLAUDE.md rule 2).
 */
interface HomeLayoutRepository {
    fun layout(): Flow<HomeLayout>

    /**
     * Fail-CLOSED point read of the current layout: reads the store once and lets an
     * IOException propagate rather than recovering to the empty [HomeLayout] default.
     *
     * Use this — never the fail-open [layout] flow — whenever a read feeds a DESTRUCTIVE
     * decision, e.g. computing which keys a reconcile may prune. Through [layout] a
     * transient read error would surface as an empty layout, which reads as "nothing to
     * protect" and silently drops every prune-protection; [snapshot] instead aborts the
     * pass (skip, retry next event). Same posture and same underlying read [update] uses
     * internally (DSR snapshotFailClosed).
     */
    suspend fun snapshot(): HomeLayout

    /** Full replace, serialized against [update] and other [save] calls. */
    suspend fun save(layout: HomeLayout)

    /**
     * Atomic read-modify-write: [transform] receives the current layout and
     * returns the new one, or null to leave it unchanged (no write). The whole
     * read → transform → write runs under a single writer lock, so concurrent
     * mutations serialize instead of racing on a stale read (AUDIT-1 A1-03).
     *
     * [transform] must be pure with respect to this repository: it must NOT call
     * back into [save] or [update], which would deadlock on the non-reentrant
     * writer lock. It receives the current layout as its argument — that is the
     * only read it needs.
     */
    suspend fun update(transform: suspend (HomeLayout) -> HomeLayout?)

    /**
     * First-run seed: if no layout has ever been persisted, save one that places the
     * apps from [resolveDockApps] in the dock (order preserved) and the apps from
     * [resolveGridApps] on the grid (row-major from the top-left, e.g. the Play Store),
     * on top of the default grid; if a layout already exists this is a no-op. Returns
     * true only when it actually seeded. Both resolvers are invoked ONLY when a seed
     * will happen — so a returning install (or one whose layout is already established)
     * never pays for resolving the apps (which does system IPCs).
     *
     * The first-run gate and the seeding write happen under the same writer lock, so
     * a concurrent [update] from a background reconcile can't slip a layout in between
     * the check and the write (AUDIT-1 A1-03).
     */
    suspend fun seedInitialLayout(
        resolveDockApps: suspend () -> List<ComponentKey>,
        resolveGridApps: suspend () -> List<ComponentKey>,
    ): Boolean
}
