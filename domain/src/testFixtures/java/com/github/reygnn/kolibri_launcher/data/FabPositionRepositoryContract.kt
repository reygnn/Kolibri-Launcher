package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * FAB POSITION REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * The [FabPositionRepository] surface is small (one Flow + one suspend
 * setter + the inherited [com.github.reygnn.kolibri_launcher.domain.repository.Purgeable.purgeRepository])
 * but has one non-obvious invariant worth pinning: when nothing has
 * ever been persisted (fresh install, post-purge), the flow MUST emit
 * [FabPosition.DEFAULT] rather than failing or stalling. Consumers
 * read the flow on edit-mode entry and immediately apply the position
 * — a missing emission would leave the FAB unrendered.
 *
 * NICHT IM CONTRACT (Manager-spezifisch):
 *   - The impl rethrows save exceptions for `launchSafe` wrapping in
 *     the ViewModel; the fake never throws. Error-propagation is
 *     implementation detail.
 *   - `shareIn` lifecycle would warrant a separate
 *     `FabPositionRepositoryImplShareInTest` if observable starvation
 *     ever shows up — not needed today.
 *
 * @see FakeFabPositionRepositoryContractTest
 * @see FabPositionRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class FabPositionRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): FabPositionRepository

    // ---------- Initial state ----------

    @Test
    fun `fresh repository emits DEFAULT position`() = runTest {
        val repo = createRepository()
        assertEquals(FabPosition.DEFAULT, repo.fabPositionFlow.first())
    }

    // ---------- saveFabPosition roundtrip ----------

    @Test
    fun `saveFabPosition reflects in flow`() = runTest {
        val repo = createRepository()
        val position = FabPosition(xFraction = 0.25f, yFraction = 0.5f)
        repo.saveFabPosition(position)
        assertEquals(position, repo.fabPositionFlow.first())
    }

    @Test
    fun `saveFabPosition overwrites previous value`() = runTest {
        val repo = createRepository()
        repo.saveFabPosition(FabPosition(xFraction = 0.1f, yFraction = 0.1f))
        val newPosition = FabPosition(xFraction = 0.9f, yFraction = 0.9f)
        repo.saveFabPosition(newPosition)
        assertEquals(newPosition, repo.fabPositionFlow.first())
    }

    /**
     * The two axes are persisted independently. Saving a position with
     * a distinctive x and y catches an impl that accidentally reads
     * back the same key for both axes (copy-paste hazard with two
     * float keys).
     */
    @Test
    fun `saveFabPosition preserves both axes independently`() = runTest {
        val repo = createRepository()
        val position = FabPosition(xFraction = 0.123f, yFraction = 0.876f)
        repo.saveFabPosition(position)
        val read = repo.fabPositionFlow.first()
        assertEquals(0.123f, read.xFraction)
        assertEquals(0.876f, read.yFraction)
    }

    /**
     * Out-of-range values are persisted as-is. Clamping is the
     * consumer's responsibility per [FabPosition] — the repository
     * must not silently rewrite the value, or callers would see drift
     * between what they save and what they read back.
     */
    @Test
    fun `saveFabPosition does not clamp out-of-range values`() = runTest {
        val repo = createRepository()
        val position = FabPosition(xFraction = -0.5f, yFraction = 1.5f)
        repo.saveFabPosition(position)
        val read = repo.fabPositionFlow.first()
        assertEquals(-0.5f, read.xFraction)
        assertEquals(1.5f, read.yFraction)
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository restores DEFAULT`() = runTest {
        val repo = createRepository()
        repo.saveFabPosition(FabPosition(xFraction = 0.3f, yFraction = 0.7f))
        repo.purgeRepository()
        assertEquals(FabPosition.DEFAULT, repo.fabPositionFlow.first())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertEquals(FabPosition.DEFAULT, repo.fabPositionFlow.first())
    }
}
