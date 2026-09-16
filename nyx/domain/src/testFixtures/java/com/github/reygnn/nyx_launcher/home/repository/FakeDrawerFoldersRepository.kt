package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [DrawerFoldersRepository] test double. Backed by a StateFlow so
 * [folders] re-emits after every write; exposes [current] and [updateCount] for
 * use-case tests. [update] holds a [Mutex] across read-transform-write, mirroring
 * the impl's atomicity so the shared contract's concurrency case means the same on
 * both sides.
 */
class FakeDrawerFoldersRepository(
    initial: DrawerFolders = DrawerFolders.EMPTY,
) : DrawerFoldersRepository {

    private val state = MutableStateFlow(initial)
    private val writeMutex = Mutex()

    val current: DrawerFolders get() = state.value

    var updateCount = 0
        private set

    override fun folders(): Flow<DrawerFolders> = state

    override suspend fun update(transform: suspend (DrawerFolders) -> DrawerFolders?) =
        writeMutex.withLock {
            transform(state.value)?.let {
                updateCount++
                state.value = it
            }
            Unit
        }
}
