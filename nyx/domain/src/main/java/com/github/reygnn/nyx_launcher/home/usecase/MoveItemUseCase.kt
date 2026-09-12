package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * Thin IO shell over the pure [HomeLayoutTransition.move] (MOVE_ITEM_SPEC §4):
 * read once → transform → save only on a real change. The policy is a truth
 * table (tested separately); this class only exists to do the reading, the
 * conditional write, and the dispatcher hop.
 */
class MoveItemUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(moving: ItemId, target: DropTarget): MoveResult =
        repository.runLayoutEdit(dispatcher, MoveResult.NoOp) { current ->
            HomeLayoutTransition.move(current, moving, target, idFactory::next)
        }
}
