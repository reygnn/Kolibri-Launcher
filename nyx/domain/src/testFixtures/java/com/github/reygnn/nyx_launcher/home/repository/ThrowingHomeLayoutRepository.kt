package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A [HomeLayoutRepository] test double whose WRITE paths fail, to pin that a
 * failed persist propagates out of a use-case rather than being swallowed and
 * dressed up as success (B1). [update] runs the transform first (so a genuine
 * change is computed) and throws only when the transform would actually write —
 * mirroring the impl, where a `null` transform result skips the write entirely.
 *
 * [layout] re-emits [current] once; [seedInitialDock] is unused by the write-path
 * tests and throws if touched.
 */
class ThrowingHomeLayoutRepository(
    private val current: HomeLayout,
    private val error: () -> Throwable = { IllegalStateException("simulated persist failure") },
) : HomeLayoutRepository {

    override fun layout(): Flow<HomeLayout> = flowOf(current)

    override suspend fun save(layout: HomeLayout): Unit = throw error()

    override suspend fun update(transform: suspend (HomeLayout) -> HomeLayout?) {
        // A no-op transform (null) writes nothing and so cannot fail; only a real
        // change reaches the (failing) write.
        if (transform(current) != null) throw error()
    }

    override suspend fun seedInitialDock(resolveDockApps: suspend () -> List<ComponentKey>): Boolean =
        throw error()
}
