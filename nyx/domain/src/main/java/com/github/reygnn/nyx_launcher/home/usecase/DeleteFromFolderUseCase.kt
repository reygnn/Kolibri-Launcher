package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * Thin IO shell over [HomeLayoutTransition.deleteFromFolder]: read once → transform → save
 * only on a real change. Removes a DEAD (uninstalled) member from a folder with no
 * placement — the folder-internal analog of [RemoveItemUseCase] for a top-level tile.
 * Auto-dissolve (folder → 1 member) is the transition's job.
 */
class DeleteFromFolderUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(folder: ItemId, member: ComponentKey): LayoutEdit =
        repository.runLayoutEdit(dispatcher, LayoutEdit.NoOp) { current ->
            HomeLayoutTransition.deleteFromFolder(current, folder, member, idFactory::next)
        }
}
