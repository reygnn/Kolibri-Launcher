package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import kotlinx.coroutines.flow.Flow

/**
 * The single data-access seam for the home layout (CLAUDE.md rule 1).
 *
 * [layout] is a cold [Flow] — one authoritative read path, no hot-share
 * parameter (mirrors the big Kolibri's DATASTORE_READ_SPEC posture). Every
 * mutation goes through [save]; there is no partial-update API, because the
 * layout is persisted as one versioned blob (ICON_HOME_MODEL_SPEC §7-E1).
 *
 * Contract + triple: `HomeLayoutRepositoryContract` (abstract),
 * `FakeHomeLayoutRepositoryContractTest`, and — once `:data` lands —
 * `HomeLayoutRepositoryImplContractTest` (CLAUDE.md rule 2).
 */
interface HomeLayoutRepository {
    fun layout(): Flow<HomeLayout>
    suspend fun save(layout: HomeLayout)
}
