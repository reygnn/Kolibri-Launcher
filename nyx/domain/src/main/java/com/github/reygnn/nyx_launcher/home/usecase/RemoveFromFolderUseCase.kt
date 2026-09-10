package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.di.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.FolderEditResult
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Thin IO shell over [HomeLayoutTransition.removeFromFolder]: read once →
 * transform → save only on a real change. Auto-dissolve (folder → 1 member) is
 * the transition's job (RFF-INV-2); this class only does the IO.
 */
class RemoveFromFolderUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(
        folder: ItemId,
        member: ComponentKey,
        target: DropTarget,
    ): FolderEditResult = withContext(dispatcher) {
        val current = repository.layout().first()
        val result = HomeLayoutTransition.removeFromFolder(current, folder, member, target, idFactory::next)
        result.layout?.let { repository.save(it) }
        result
    }
}
