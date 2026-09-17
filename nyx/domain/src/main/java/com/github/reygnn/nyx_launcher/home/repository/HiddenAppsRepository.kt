package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey
import kotlinx.coroutines.flow.Flow

/**
 * The single data-access seam for the set of apps the user has hidden from the drawer
 * (CLAUDE.md rule 1), separate from [DrawerFoldersRepository] and [HomeLayoutRepository]:
 * hiding is its own persisted concern.
 *
 * [hidden] is a cold [Flow] over the current hidden set (mirrors
 * [DrawerFoldersRepository.folders]); a missing/undecodable blob reads as the empty set,
 * never a crash. Hiding is a DISPLAY-only concern: the set is filtered out of the drawer
 * projection ([com.github.reygnn.nyx_launcher.home.usecase.GetDrawerContentUseCase]); an
 * uninstalled key simply never matches, so no cleanup is required.
 *
 * Mutations go through [update] — an ATOMIC read-modify-write so concurrent edits (a
 * context-menu hide + a settings-manager change) serialize instead of racing on a stale
 * read (mirrors A1-03).
 *
 * Contract + triple: `HiddenAppsRepositoryContract` (abstract),
 * `FakeHiddenAppsRepositoryContractTest`, `HiddenAppsRepositoryImplContractTest`
 * (CLAUDE.md rule 2).
 */
interface HiddenAppsRepository {
    fun hidden(): Flow<Set<ComponentKey>>

    /**
     * Atomic read-modify-write: [transform] receives the current hidden set and returns the
     * new one, or `null` to leave it unchanged (no write). The whole read → transform →
     * write runs under one writer lock.
     *
     * [transform] must NOT call back into [update] on this repository (the writer lock is
     * non-reentrant); it already receives the current value as its argument.
     */
    suspend fun update(transform: suspend (Set<ComponentKey>) -> Set<ComponentKey>?)
}
