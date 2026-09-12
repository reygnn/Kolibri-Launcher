package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** Removes a top-level item from the home (HEU-INV-2: member apps aren't lost). */
class RemoveItemUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(id: ItemId): LayoutEdit =
        repository.runLayoutEdit(dispatcher, LayoutEdit.NoOp) { current ->
            HomeLayoutTransition.remove(current, id)
        }
}
