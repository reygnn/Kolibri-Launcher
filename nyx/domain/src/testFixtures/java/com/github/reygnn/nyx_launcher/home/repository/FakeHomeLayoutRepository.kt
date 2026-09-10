package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [HomeLayoutRepository] test double. Backed by a StateFlow so
 * [layout] re-emits after every [save]. Exposes [current] and [saveCount] so
 * use-case tests can assert the "save only on change" contract (MIU-INV-3).
 */
class FakeHomeLayoutRepository(initial: HomeLayout) : HomeLayoutRepository {

    private val state = MutableStateFlow(initial)

    var saveCount = 0
        private set

    val current: HomeLayout get() = state.value

    override fun layout(): Flow<HomeLayout> = state

    override suspend fun save(layout: HomeLayout) {
        saveCount++
        state.value = layout
    }
}
