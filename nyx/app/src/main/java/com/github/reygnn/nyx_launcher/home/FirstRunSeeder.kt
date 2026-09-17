package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.data.DefaultAppsResolver
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderIdFactory
import com.github.reygnn.nyx_launcher.home.model.DrawerVendorGrouping
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * First-run defaults, so a fresh install (or a factory reset) doesn't land on an empty
 * home/drawer. Both seeds are one-shot and gated inside their repositories, so calling
 * them on every start is cheap and a returning install is a no-op — the resolvers here
 * run only when a seed actually happens.
 *
 * Two independent seeds:
 * - [seedHomeLayout]: the device's default dock apps plus the Play Store on the grid.
 * - [seedDrawerFolders]: all of the device's Google apps into one "Google" drawer folder.
 *
 * Shared by [MainActivity] (first launch) and the settings factory-reset, which both
 * need the exact same default state.
 */
class FirstRunSeeder @Inject constructor(
    private val homeLayoutRepository: HomeLayoutRepository,
    private val drawerFoldersRepository: DrawerFoldersRepository,
    private val defaultAppsResolver: DefaultAppsResolver,
    private val getDrawerApps: GetDrawerAppsUseCase,
    private val drawerFolderIdFactory: DrawerFolderIdFactory,
) {
    /** Seed dock defaults + Play Store on the grid. Returns true only if it wrote. */
    suspend fun seedHomeLayout(): Boolean = homeLayoutRepository.seedInitialLayout(
        resolveDockApps = { withContext(Dispatchers.Default) { defaultAppsResolver.resolveDockApps() } },
        resolveGridApps = { withContext(Dispatchers.Default) { defaultAppsResolver.resolveGridApps() } },
    )

    /** Seed a single "Google" drawer folder from the installed Google apps (≥ 2 needed). */
    suspend fun seedDrawerFolders(): Boolean = drawerFoldersRepository.seedInitialFolders {
        withContext(Dispatchers.Default) {
            val google = DrawerVendorGrouping.groups(getDrawerApps())
                .firstOrNull { it.label == GOOGLE_LABEL } ?: return@withContext emptyList()
            listOf(DrawerFolder(drawerFolderIdFactory.next(), title = GOOGLE_LABEL, members = google.keys))
        }
    }

    private companion object {
        // Matches DrawerVendorGrouping's curated label for the com.google.* prefix; the
        // folder title is shown verbatim (vendor labels are not string resources).
        const val GOOGLE_LABEL = "Google"
    }
}
