package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.RegridOutcome
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutRegridder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Re-fits the persisted layout onto a [target] grid via the pure
 * [HomeLayoutRegridder]: read once → transform → save only on a real change.
 *
 * The [target] is derived by the UI from the *actual* home-grid area (see
 * MainActivity), not a pre-layout metrics estimate — that's the only way the row
 * count matches the space exactly, so the bottom-anchored grid leaves at most a
 * sub-cell margin at the top. No item is ever lost (regridder repacks off-grid
 * items).
 */
class FitHomeGridUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(target: GridSpec) = withContext(dispatcher) {
        repository.update { current -> // atomic RMW (A1-03)
            when (val outcome = HomeLayoutRegridder.fit(current, target)) {
                RegridOutcome.Unchanged -> null
                is RegridOutcome.Changed -> outcome.layout
            }
        }
    }
}
