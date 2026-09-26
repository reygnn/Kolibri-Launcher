package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.LayoutSerializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Imports a backed-up layout: parse → save → reconcile. The reconcile pass is
 * STRUCTURAL ONLY (dedup RHL-INV-4 / folder-repair / trailing-page-trim); it does
 * NOT prune and does NOT enumerate installed apps. A reference to an app not
 * installed on this device is KEPT (Windows-shortcut model) — it renders greyed as
 * a "missing" tile and is removed lazily in the UI, never auto-dropped on import.
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
        // reconcile() is total (fail-closed to a ReconcileResult.Skipped value, only
        // CancellationException escapes), so no guard is needed here: a transient store
        // fault leaves the just-saved layout in place and the structural cleanup (dedup /
        // folder-repair / page-trim) simply defers to the next reconcile (edit / cold start
        // / package event). The saved import is the success; the cleanup pass is best-effort.
        reconcile()
        ImportResult.Success
    }
}
