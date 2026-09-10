package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.di.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
        withContext(dispatcher) {
            val current = repository.layout().first()
            val result = HomeLayoutTransition.place(current, app, target, idFactory::next)
            result.layout?.let { repository.save(it) }
            result
        }
}
