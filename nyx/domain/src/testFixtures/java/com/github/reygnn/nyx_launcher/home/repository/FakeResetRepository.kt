package com.github.reygnn.nyx_launcher.home.repository

/** Configurable stub of [ResetRepository] for UI tests; counts the resets. */
class FakeResetRepository(var complete: Boolean = true) : ResetRepository {
    var resetCount = 0
        private set

    override suspend fun factoryReset(): Boolean {
        resetCount++
        return complete
    }
}
