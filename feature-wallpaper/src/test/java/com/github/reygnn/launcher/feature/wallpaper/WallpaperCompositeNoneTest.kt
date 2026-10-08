package com.github.reygnn.launcher.feature.wallpaper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * [WallpaperComposite.None] (3b-3, M2): no composite work and nothing to read — but [exclusive] is
 * a real lock, so "remove wallpaper" keeps its lock order in an app without a composite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperCompositeNoneTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val twoLayers = WallpaperState.multiLayer(
        listOf(WallpaperLayerState(id = "a", imageUri = "file:///w/a"), WallpaperLayerState(id = "b", imageUri = "file:///w/b")),
    )

    @Test
    fun refill_does_no_work_and_the_read_side_finds_nothing() {
        val composite = WallpaperComposite.None()
        val host = mockk<WallpaperComposite.Host>(relaxed = true)

        composite.refill(twoLayers, host)
        composite.invalidate(dropLuminance = true)

        verify(exactly = 0) { host.launch(any()) }
        assertThat(composite.cachedKeyFor(twoLayers, 1080, 2340)).isNull()
        assertThat(composite.cachedBitmap("composite://any")).isNull()
    }

    @Test
    fun exclusive_is_a_real_lock() = runTest(mainDispatcherRule.testDispatcher) {
        val composite = WallpaperComposite.None()
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        launch { composite.exclusive { order += "first in"; gate.await(); order += "first out" } }
        launch { composite.exclusive { order += "second" } }
        advanceUntilIdle()
        assertThat(order).containsExactly("first in") // the second waits for the lock

        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(order).containsExactly("first in", "first out", "second").inOrder()
        assertThat(composite.exclusive { 42 }).isEqualTo(42)
    }
}
