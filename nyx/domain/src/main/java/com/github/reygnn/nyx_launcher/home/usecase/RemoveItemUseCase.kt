package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.di.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Removes a top-level item from the home (HEU-INV-2: member apps aren't lost). */
class RemoveItemUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(id: ItemId): LayoutEdit = withContext(dispatcher) {
        val current = repository.layout().first()
        HomeLayoutTransition.remove(current, id).also { edit -> edit.layout?.let { repository.save(it) } }
    }
}
