package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.di.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.LayoutSerializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Imports a backed-up layout: parse → save → reconcile. The reconcile pass is
 * where an imported layout's duplicates get de-duplicated (RHL-INV-4) and any
 * apps not installed on this device get pruned — fail-closed, so a broken import
 * (or a transient enumeration failure) can't leave a half-applied home screen.
 * Unparseable input is rejected before any save.
 */
class ImportLayoutUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    private val serializer: LayoutSerializer,
    private val reconcile: ReconcileHomeLayoutUseCase,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(raw: String): ImportResult = withContext(dispatcher) {
        val layout = serializer.deserialize(raw) ?: return@withContext ImportResult.InvalidData
        repository.save(layout)
        reconcile()
        ImportResult.Success
    }
}
