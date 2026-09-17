package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import kotlinx.coroutines.flow.Flow

/**
 * The single data-access seam for drawer-folder membership (CLAUDE.md rule 1),
 * deliberately SEPARATE from [HomeLayoutRepository] (DRAWER_FOLDERS_SPEC §4 / D-5):
 * home and drawer folders share no persisted state.
 *
 * [folders] is a cold [Flow] over the current membership (mirrors
 * [HomeLayoutRepository.layout]); a missing/undecodable blob reads as
 * [DrawerFolders.EMPTY], never a crash. Mutations go through [update] — an ATOMIC
 * read-modify-write so concurrent edits (a drawer drop + a lazy reconcile write)
 * serialize instead of racing on a stale read (mirrors A1-03).
 *
 * Contract + triple: `DrawerFoldersRepositoryContract` (abstract),
 * `FakeDrawerFoldersRepositoryContractTest`, `DrawerFoldersRepositoryImplContractTest`
 * (CLAUDE.md rule 2).
 */
interface DrawerFoldersRepository {
    fun folders(): Flow<DrawerFolders>

    /**
     * Atomic read-modify-write: [transform] receives the current membership and
     * returns the new one, or `null` to leave it unchanged (no write). The whole
     * read → transform → write runs under one writer lock.
     *
     * [transform] must NOT call back into [update] on this repository (the writer
     * lock is non-reentrant); it already receives the current value as its argument.
     */
    suspend fun update(transform: suspend (DrawerFolders) -> DrawerFolders?)

    /**
     * First-run seed: if no drawer folders have ever been persisted, save the folders
     * returned by [resolveFolders] (each must hold ≥ 2 members per DFOLD-INV-1, or it
     * is dropped); if any folders already exist this is a no-op. Returns true only when
     * it actually seeded. [resolveFolders] is invoked ONLY when a seed will happen — so
     * a returning install (or one whose folders are already established, e.g. from an
     * import) never pays for resolving the installed-app list.
     *
     * The first-run gate and the seeding write happen under the same writer lock, so a
     * concurrent [update] can't slip folders in between the check and the write (A1-03).
     */
    suspend fun seedInitialFolders(resolveFolders: suspend () -> List<DrawerFolder>): Boolean
}
