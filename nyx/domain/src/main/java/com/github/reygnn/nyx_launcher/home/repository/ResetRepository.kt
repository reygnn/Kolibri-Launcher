package com.github.reygnn.nyx_launcher.home.repository

/**
 * Nyx's factory reset (2b-4c): purges every store a reset covers, each on its own. One button, so
 * one operation; Kolibri splits the same work into three steps behind its `FactoryResetUseCase`.
 * A shared result type for both apps is open question O4 (c).
 */
interface ResetRepository {
    /**
     * True when every store was purged. False means the reset is incomplete: every store ran, but
     * at least one failed — the others are purged regardless.
     */
    suspend fun factoryReset(): Boolean
}
