package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [HomeLayoutRepository] test double. Backed by a StateFlow so
 * [layout] re-emits after every write. Exposes [current] and [saveCount] so
 * use-case tests can assert the "save only on change" contract (MIU-INV-3).
 *
 * [update] holds a [Mutex] across read-transform-write, mirroring the impl's
 * atomicity (A1-03) so the shared contract's concurrency case means the same on
 * both sides. [save] takes the same lock; neither re-enters the other.
 */
class FakeHomeLayoutRepository(initial: HomeLayout) : HomeLayoutRepository {

    private val state = MutableStateFlow(initial)
    private val writeMutex = Mutex()

    var saveCount = 0
        private set

    val current: HomeLayout get() = state.value

    override fun layout(): Flow<HomeLayout> = state

    override suspend fun save(layout: HomeLayout) = writeMutex.withLock {
        saveCount++
        state.value = layout
    }

    override suspend fun update(transform: suspend (HomeLayout) -> HomeLayout?) =
        writeMutex.withLock {
            transform(state.value)?.let {
                saveCount++
                state.value = it
            }
            Unit
        }
}
