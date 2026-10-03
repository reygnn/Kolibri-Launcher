package com.github.reygnn.launcher.core.wallpaper

import com.github.reygnn.launcher.core.Purgeable
import kotlinx.coroutines.flow.Flow

/**
 * Shared by both apps since 3a-5 (in `:core` because Kolibri's pure-JVM domain uses it; the
 * implementation is `FabPositionStore` in `:feature-wallpaper`).
 *
 * Persists the position of the wallpaper-edit speed-dial FAB so that
 * the FAB reappears where the user last dragged it on the next edit-mode
 * entry.
 *
 * Value semantics live on [FabPosition].
 */
interface FabPositionRepository : Purgeable {
    /**
     * Current FAB position. Emits [FabPosition.DEFAULT] when no value
     * has been persisted yet, so callers do not need to handle a
     * not-present case.
     */
    val fabPositionFlow: Flow<FabPosition>

    /**
     * Persists the given FAB position. No clamping is performed here —
     * the caller is expected to have already constrained the value to
     * its visible range.
     */
    suspend fun saveFabPosition(position: FabPosition)
}
