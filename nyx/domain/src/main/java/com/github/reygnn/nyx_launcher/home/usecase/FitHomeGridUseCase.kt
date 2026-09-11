package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.RegridOutcome
import com.github.reygnn.nyx_launcher.home.repository.GridSpecProvider
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutRegridder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Re-fits the persisted layout onto the device-derived grid ([GridSpecProvider])
 * via the pure [HomeLayoutRegridder]: read once → transform → save only on a real
 * change. Runs at cold start (see PackageEventCoordinator) so the grid adapts to
 * the current screen — more rows/columns on a larger device — without losing any
 * placed item.
 */
class FitHomeGridUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    private val gridProvider: GridSpecProvider,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke() = withContext(dispatcher) {
        val target = gridProvider.deviceGrid()
        val current = repository.layout().first()
        when (val outcome = HomeLayoutRegridder.fit(current, target)) {
            RegridOutcome.Unchanged -> Unit
            is RegridOutcome.Changed -> repository.save(outcome.layout)
        }
    }
}
