package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutTransition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Sets a folder's title (unchanged title / non-folder id ⇒ no save). */
class RenameFolderUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(folder: ItemId, title: String): LayoutEdit = withContext(dispatcher) {
        var result: LayoutEdit = LayoutEdit.NoOp
        repository.update { current -> // atomic RMW (A1-03)
            result = HomeLayoutTransition.renameFolder(current, folder, title)
            result.layout
        }
        result
    }
}
