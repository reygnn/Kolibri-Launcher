package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.InstalledAppsRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutReconciler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Reconciles the persisted layout against installed apps. FAIL-CLOSED (RHL-INV-1):
 * the pure reconciler runs ONLY against a genuine [AppLoadResult.Loaded]; an
 * [AppLoadResult.Error] returns [ReconcileResult.Skipped] with zero mutation and
 * zero save — a transient enumeration failure never empties the home screen.
 */
class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepository: HomeLayoutRepository,
    private val appsRepository: InstalledAppsRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): ReconcileResult = withContext(dispatcher) {
        when (val load = appsRepository.loadInstalledApps()) {
            is AppLoadResult.Error -> ReconcileResult.Skipped(load.reason)
            is AppLoadResult.Loaded -> {
                val installed = load.apps.mapTo(HashSet()) { it.key }
                var result: ReconcileResult = ReconcileResult.Unchanged
                layoutRepository.update { current -> // atomic RMW (A1-03)
                    when (val outcome = HomeLayoutReconciler.reconcile(current, installed, idFactory::next)) {
                        ReconcileOutcome.Unchanged -> null
                        is ReconcileOutcome.Changed -> {
                            result = ReconcileResult.Reconciled(outcome.report)
                            outcome.layout
                        }
                    }
                }
                result
            }
        }
    }
}
