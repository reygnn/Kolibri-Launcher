package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.model.displayName
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

/**
 * The drawer as a derived, self-healing projection (DRAWER_FOLDERS_SPEC §5): it
 * combines the live app list with the persisted folder membership into the display
 * list. Re-emits whenever either source changes — a folder created/renamed, or the
 * drawer re-queried on open (A1-04).
 *
 * [apps] is the drawer's live app source (the ViewModel's refreshed StateFlow, sorted
 * upstream by [GetDrawerAppsUseCase]); folders come from [DrawerFoldersRepository].
 * The actual projection is the pure [projectDrawerContent] — testable off the Android
 * runtime and free of Flow/dispatcher concerns.
 */
class GetDrawerContentUseCase @Inject constructor(
    private val drawerFoldersRepository: DrawerFoldersRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val appUsageRepository: AppUsageRepository,
) {
    /**
     * [apps] is the live drawer app source; [revealHidden] is the transient overflow toggle;
     * [usageSortEnabled] is the drawer sort-mode setting (both supplied by the ViewModel, like
     * [revealHidden]). With reveal off, hidden apps are filtered out; with reveal on, they stay
     * (marked [DrawerEntry.App.hidden] so the UI can dim them). Loose apps are ordered
     * alphabetically by default, or by time-weighted usage (most-used first) when
     * [usageSortEnabled] — a launch ticks the usage snapshot and re-orders reactively.
     *
     * `flatMapLatest` on the sort setting keeps the usage snapshot flow collected ONLY in
     * usage mode (mirrors kolibri): in alphabetical mode a per-launch usage tick must not
     * re-run the projection just to emit an identical list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        apps: Flow<List<LauncherApp>>,
        revealHidden: Flow<Boolean>,
        usageSortEnabled: Flow<Boolean>,
    ): Flow<List<DrawerEntry>> =
        usageSortEnabled.distinctUntilChanged().flatMapLatest { usageSort ->
            if (usageSort) {
                combine(
                    apps,
                    drawerFoldersRepository.folders(),
                    hiddenAppsRepository.hidden(),
                    revealHidden,
                    appUsageRepository.usageSnapshotFlow,
                ) { a, folders, hidden, reveal, snapshot ->
                    projectDrawerContent(a, folders, hidden, reveal, usageSort = true, usageScores = appUsageRepository.scoreApps(a, snapshot))
                }
            } else {
                combine(
                    apps,
                    drawerFoldersRepository.folders(),
                    hiddenAppsRepository.hidden(),
                    revealHidden,
                ) { a, folders, hidden, reveal ->
                    projectDrawerContent(a, folders, hidden, reveal)
                }
            }
        }
}

/**
 * PURE projection: `(live apps) ⊕ (persisted membership) → pinned folder block, then
 * loose apps`. No Android, no Flow, fully testable.
 *
 * - **Reconcile is display-only** (D-6): each folder's members are intersected with
 *   the live app set, so an uninstalled member drops immediately (DFOLD-INV-4);
 *   persistence cleanup is lazy elsewhere.
 * - **Auto-dissolve on reconcile** (DFOLD-INV-1): a folder left with fewer than two
 *   resolvable members is not shown as a folder — its remaining member falls back
 *   into the loose app pool.
 * - **Placement (D-2):** folders first, alphabetically by title; then the loose apps
 *   (those in no surviving folder), alphabetically by display name (`customName ?:
 *   label`). A blank folder title sorts as empty here — the localized default name is
 *   resolved in the UI (§10), so blank-titled folders cluster together.
 * - **Hidden apps** ([hidden]) are removed from BOTH the loose pool and folder members
 *   (display-only — the persisted membership is untouched, so a folder that drops below two
 *   visible members re-appears when its members are unhidden). With [revealHidden] on
 *   (overflow "show hidden"), nothing is filtered and each revealed loose app is marked
 *   [DrawerEntry.App.hidden] so the UI dims it.
 * - **Loose-app order** is alphabetical by display name by default. With [usageSort] on,
 *   loose apps are ordered by [usageScores] descending (most-used first) with an
 *   alphabetical tie-break. Folders always stay pinned first, alphabetically by title,
 *   regardless of sort mode.
 */
internal fun projectDrawerContent(
    apps: List<LauncherApp>,
    folders: DrawerFolders,
    hidden: Set<ComponentKey> = emptySet(),
    revealHidden: Boolean = false,
    usageSort: Boolean = false,
    usageScores: Map<ComponentKey, Double> = emptyMap(),
): List<DrawerEntry> {
    val byKey = apps.associateBy { it.key }

    val reconciled = folders.folders
        .map { folder -> folder.copy(members = folder.members.filter { it in byKey && (revealHidden || it !in hidden) }) }
        .filter { it.members.size >= 2 }

    val memberKeys = reconciled.flatMapTo(HashSet()) { it.members }

    val folderEntries = reconciled
        .sortedBy { it.title.lowercase() }
        .map { DrawerEntry.Folder(it.id, it.title, it.members) }

    val looseOrder = if (usageSort) {
        compareByDescending<LauncherApp> { usageScores[it.key] ?: 0.0 }.thenBy { it.displayName.lowercase() }
    } else {
        compareBy { it.displayName.lowercase() }
    }
    val appEntries = apps
        .filterNot { it.key in memberKeys || (!revealHidden && it.key in hidden) }
        .sortedWith(looseOrder)
        .map { DrawerEntry.App(it, hidden = it.key in hidden) }

    return folderEntries + appEntries
}
