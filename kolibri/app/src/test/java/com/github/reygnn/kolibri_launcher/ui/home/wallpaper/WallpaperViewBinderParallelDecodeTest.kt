package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the two properties that the parallel decode phase of
 * [WallpaperViewBinder.applyFullRebuild] adds on top of the existing behaviour:
 *
 * 1. **Concurrency is capped** at `maxParallelDecodes` — the per-render `Semaphore`
 *    never lets more than `cap` decodes run at once.
 * 2. **Z-order is plan order**, regardless of which decode finishes first — `awaitAll`
 *    preserves input order, so the add phase rebuilds the layer stack exactly.
 *
 * Both are exercised via cooperative suspension on a single-threaded
 * `StandardTestDispatcher` (the default in `runTest`): the loader suspends on a
 * controllable signal, and `runCurrent()` lets exactly the runnable decodes proceed.
 * No `MainDispatcherRule` — the binder never touches `Dispatchers.Main`; it is
 * dispatcher-agnostic and runs on the test scheduler. Keeping it that way is what
 * makes these tests deterministic (WALLPAPER_PARALLEL_DECODE_SPEC §6.7).
 *
 * Robolectric for the same reason as the sibling binder tests: the binder mutates a
 * real [ZoomableImageView] and the plan carries real `Uri`s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperViewBinderParallelDecodeTest {

    @get:Rule
    val timberRule = TimberRule()

    // --- Fixtures ---

    private fun view() = ZoomableImageView(ApplicationProvider.getApplicationContext())

    private fun layer(id: String) = WallpaperLayerState(
        id = id,
        imageUri = "file:///data/$id.jpg",
    )

    private fun stateOf(vararg ids: String) =
        WallpaperState.multiLayer(ids.map { layer(it) })

    private fun bitmap() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    // ===========================================
    // CONCURRENCY CAP — at most `cap` decodes run at once
    // ===========================================

    @Test
    fun `decode concurrency is capped at maxParallelDecodes`() = runTest {
        val cap = 2
        val release = CompletableDeferred<Unit>()
        // Plain Int, not AtomicInteger: the scheduler is single-threaded, so there is
        // no real contention — "max simultaneously suspended inside the loader" is
        // exactly what the semaphore bounds.
        var current = 0
        var maxObserved = 0

        val binder = WallpaperViewBinder(maxParallelDecodes = cap) { _ ->
            current++
            maxObserved = maxOf(maxObserved, current)
            release.await() // suspend inside the loader until the test lets go
            current--
            DecodedWallpaperBitmap(bitmap(), 1, 8, 8)
        }

        // Launch bind as a child and inspect it WHILE suspended: runCurrent drains
        // every runnable decode, so as many as `cap` permits allow enter the loader
        // and park on `release`; the rest block at Semaphore.acquire().
        val job = launch { binder.bind(view(), stateOf("L0", "L1", "L2", "L3", "L4")) }
        testScheduler.runCurrent()

        // == cap, not <= cap: a <= bound passes vacuously for a fully-serialized
        // (broken) impl whose max would be 1. Only == pins the liveness property that
        // the semaphore actually lets `cap` through.
        assertEquals(
            "the semaphore must let exactly `cap` decodes run concurrently",
            cap,
            maxObserved,
        )

        // Teardown hygiene: release the gate and drain, or runTest throws
        // UncompletedCoroutinesError for the still-suspended bind child.
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        job.join()
    }

    // ===========================================
    // Z-ORDER — plan order, not completion order
    // ===========================================

    @Test
    fun `layers are added in plan order regardless of decode completion order`() = runTest {
        // One gate per layer, so the test controls decode completion ORDER.
        val gates = mapOf(
            "L0" to CompletableDeferred<Unit>(),
            "L1" to CompletableDeferred<Unit>(),
            "L2" to CompletableDeferred<Unit>(),
        )

        val binder = WallpaperViewBinder(maxParallelDecodes = 4) { uri ->
            val id = uri.lastPathSegment!!.removeSuffix(".jpg")
            gates.getValue(id).await()
            DecodedWallpaperBitmap(bitmap(), 1, 8, 8)
        }

        val view = view()
        val job = launch { binder.bind(view, stateOf("L0", "L1", "L2")) }
        testScheduler.runCurrent() // all three decodes launched and parked on their gates

        // Complete in REVERSE — the last-planned layer finishes first. If the add
        // phase used completion order instead of plan order, the view stack would
        // come out [L2, L1, L0].
        gates.getValue("L2").complete(Unit); testScheduler.runCurrent()
        gates.getValue("L1").complete(Unit); testScheduler.runCurrent()
        gates.getValue("L0").complete(Unit)
        testScheduler.advanceUntilIdle()
        job.join()

        val idsInView = (0 until view.layerCount).map { view.getLayer(it)?.id }
        assertEquals(
            "z-order must follow plan order, not decode-completion order",
            listOf("L0", "L1", "L2"),
            idsInView,
        )
    }
}
