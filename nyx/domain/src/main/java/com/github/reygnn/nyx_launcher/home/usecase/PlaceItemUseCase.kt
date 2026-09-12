package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * Places a drawer app onto the home. Uniqueness-safe (HEU-INV-1): if the app is
 * already placed, [HomeLayoutTransition.place] moves the existing item instead of
 * duplicating it. Thin IO shell: read once → transform → save on change.
 */
class PlaceItemUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(app: ComponentKey, target: DropTarget): MoveResult =
        repository.runLayoutEdit(dispatcher, MoveResult.NoOp) { current ->
            HomeLayoutTransition.place(current, app, target, idFactory::next)
        }
}
