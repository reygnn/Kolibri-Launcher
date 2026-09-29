package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * setter + the inherited [com.github.reygnn.launcher.core.Purgeable.purgeRepository])
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
 *   - No hot-share lifecycle to pin: since DATASTORE_READ_SPEC Belang A
 *     the flow is a plain cold flow (no `shareIn`), so there is no
 *     observable-starvation / stale-replay case a ShareInTest would cover.
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
        assertThat(repo.fabPositionFlow.first()).isEqualTo(FabPosition.DEFAULT)
    }

    // ---------- saveFabPosition roundtrip ----------

    @Test
    fun `saveFabPosition reflects in flow`() = runTest {
        val repo = createRepository()
        val position = FabPosition(xFraction = 0.25f, yFraction = 0.5f)
        repo.saveFabPosition(position)
        assertThat(repo.fabPositionFlow.first()).isEqualTo(position)
    }

    @Test
    fun `saveFabPosition overwrites previous value`() = runTest {
        val repo = createRepository()
        repo.saveFabPosition(FabPosition(xFraction = 0.1f, yFraction = 0.1f))
        val newPosition = FabPosition(xFraction = 0.9f, yFraction = 0.9f)
        repo.saveFabPosition(newPosition)
        assertThat(repo.fabPositionFlow.first()).isEqualTo(newPosition)
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
        assertThat(read.xFraction).isEqualTo(0.123f)
        assertThat(read.yFraction).isEqualTo(0.876f)
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
        assertThat(read.xFraction).isEqualTo(-0.5f)
        assertThat(read.yFraction).isEqualTo(1.5f)
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository restores DEFAULT`() = runTest {
        val repo = createRepository()
        repo.saveFabPosition(FabPosition(xFraction = 0.3f, yFraction = 0.7f))
        repo.purgeRepository()
        assertThat(repo.fabPositionFlow.first()).isEqualTo(FabPosition.DEFAULT)
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertThat(repo.fabPositionFlow.first()).isEqualTo(FabPosition.DEFAULT)
    }
}
