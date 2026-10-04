package com.github.reygnn.launcher.feature.wallpaper

import android.graphics.Bitmap
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperBitmapLuminanceImpl
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperCompositeCache
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattener
import com.github.reygnn.launcher.core.CompositeLuminanceSignal
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CachedWallpaperComposite] (3a-8): warm and publish, the J2 rule (published only if the host's
 * current state still has the key after the flatten) together with the follow-up refill for the
 * new state, single-flight, the edit guard and the read side. No session involved — the host is
 * plain functions.
 */
@RunWith(RobolectricTestRunner::class) // Bitmap.Config
@Config(sdk = [36])
class CachedWallpaperCompositeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val twoLayers = WallpaperState.multiLayer(
        listOf(WallpaperLayerState(id = "a", imageUri = "file:///w/a"), WallpaperLayerState(id = "b", imageUri = "file:///w/b")),
    )
    private val otherTwoLayers = WallpaperState.multiLayer(
        listOf(WallpaperLayerState(id = "b", imageUri = "file:///w/b"), WallpaperLayerState(id = "a", imageUri = "file:///w/a")),
    )

    private val hardware = mockk<Bitmap>(relaxed = true) { every { isRecycled } returns false }
    private val software = mockk<Bitmap>(relaxed = true) { every { copy(Bitmap.Config.HARDWARE, false) } returns hardware }
    private val flattener = mockk<WallpaperFlattener> { coEvery { flatten(any(), any(), any()) } returns software }
    private val luminance = mockk<WallpaperBitmapLuminanceImpl> { every { computeFromBitmap(any()) } returns 0.3f }
    private val signal = mockk<CompositeLuminanceSignal>(relaxed = true)
    private val cache = WallpaperCompositeCache()
    private val composite = CachedWallpaperComposite(cache, flattener, luminance, signal, mainDispatcherRule.testDispatcher)

    /** A host of plain functions; [current] is what "the launcher shows now". */
    private class TestHost(private val scope: CoroutineScope, var current: WallpaperState, var editing: Boolean = false) :
        WallpaperComposite.Host {
        val filled = mutableListOf<Pair<Int, Int>>()
        override fun currentState() = current
        override fun isEditing() = editing
        override fun displaySize() = 1080 to 2340
        override fun launch(block: suspend () -> Unit) {
            scope.launch { block() }
        }
        override fun onCompositeFilled(widthPx: Int, heightPx: Int) {
            filled += widthPx to heightPx
        }
    }

    @Test
    fun a_multi_layer_wallpaper_is_warmed_and_published() = runTest(mainDispatcherRule.testDispatcher) {
        val host = TestHost(this, current = twoLayers)

        composite.refill(twoLayers, host)
        advanceUntilIdle()

        val key = composite.cachedKeyFor(twoLayers, 1080, 2340)
        assertThat(key).isNotNull()
        assertThat(composite.cachedBitmap(key!!)?.bitmap).isSameInstanceAs(hardware)
        verify { signal.emit(0.3f) }
        assertThat(host.filled).containsExactly(1080 to 2340)
    }

    @Test
    fun a_state_change_during_the_flatten_discards_the_stale_composite_and_refills_for_the_new_state() =
        runTest(mainDispatcherRule.testDispatcher) {
            // J2: the host's current state is asked again AFTER the flatten; its key differs (the
            // composite key hashes every layer in order), so the stale composite is NOT published.
            // The follow-up in refill's finally then warms the new state once and publishes it —
            // Kolibri's behaviour before 3a-8, pinned here together with J2.
            val host = TestHost(this, current = twoLayers)
            coEvery { flattener.flatten(any(), any(), any()) } answers {
                host.current = otherTwoLayers
                software
            }

            composite.refill(twoLayers, host)
            advanceUntilIdle()

            assertThat(composite.cachedKeyFor(twoLayers, 1080, 2340)).isNull()
            assertThat(composite.cachedKeyFor(otherTwoLayers, 1080, 2340)).isNotNull()
            verify(exactly = 1) { signal.emit(0.3f) }
            assertThat(host.filled).containsExactly(1080 to 2340)
            coVerifyOrder {
                flattener.flatten(twoLayers, 1080, 2340)
                flattener.flatten(otherTwoLayers, 1080, 2340)
            }
        }

    @Test
    fun one_warm_at_a_time() = runTest(mainDispatcherRule.testDispatcher) {
        val host = TestHost(this, current = twoLayers)

        composite.refill(twoLayers, host)
        composite.refill(twoLayers, host) // still warming: skipped
        advanceUntilIdle()

        coVerify(exactly = 1) { flattener.flatten(any(), any(), any()) }
    }

    @Test
    fun no_composite_while_editing() = runTest(mainDispatcherRule.testDispatcher) {
        val host = TestHost(this, current = twoLayers, editing = true)

        composite.refill(twoLayers, host)
        advanceUntilIdle()

        coVerify(exactly = 0) { flattener.flatten(any(), any(), any()) }
    }

    @Test
    fun a_single_layer_target_drops_the_composite_only_if_the_current_state_needs_none() =
        runTest(mainDispatcherRule.testDispatcher) {
            val host = TestHost(this, current = twoLayers)
            composite.refill(twoLayers, host)
            advanceUntilIdle()

            // A stale single-layer target while two layers are on screen: keep the composite.
            composite.refill(WallpaperState.single(uri = "file:///w/a"), host)
            assertThat(composite.cachedKeyFor(twoLayers, 1080, 2340)).isNotNull()

            host.current = WallpaperState.single(uri = "file:///w/a")
            composite.refill(host.current, host)
            assertThat(composite.cachedKeyFor(twoLayers, 1080, 2340)).isNull()
            verify { signal.emit(null) }
        }

    @Test
    fun exclusive_runs_its_block_and_returns_its_value() = runTest(mainDispatcherRule.testDispatcher) {
        assertThat(composite.exclusive { 42 }).isEqualTo(42)
    }
}
