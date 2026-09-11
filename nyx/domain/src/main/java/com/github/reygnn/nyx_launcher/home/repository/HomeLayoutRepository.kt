package com.github.reygnn.nyx_launcher.home.repository

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
}
