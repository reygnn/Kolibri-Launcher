package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Regression guard for the Rule-9 canonical cancellation rethrow in
 * [WallpaperViewBinder]'s `applyFullRebuild` layer loop.
 *
 * `BitmapLoader.load` became `suspend` in the off-main decode change
 * (`4c09c30b`), which turned the pre-existing `catch (e: Throwable)` around
 * it into a cancellation swallower: the loop logged a `SILENT_ERROR` — and
 * therefore filed an ACRA report — every time `HomeFragment`'s render job was
 * cancelled by the next wallpaper state (latest-wins) or by `onDestroyView`.
 * Because the catch sits INSIDE the loop, it also kept decoding the remaining
 * layers on behalf of an already-dead job, one bogus report per layer.
 *
 * The two tests here pin both halves of the contract: cancellation propagates
 * and aborts the loop, real load failures stay caught and non-fatal. Reverting
 * the `catch (e: CancellationException) { throw e }` turns the first red.
 *
 * Robolectric for the same reason as [WallpaperViewBinderSingleLayerTest]: the
 * binder mutates a real [ZoomableImageView] and the plan carries real `Uri`s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperViewBinderCancellationTest {

    @get:Rule
    val timberRule = TimberRule()

    /** Every SILENT_ERROR message logged during a test, in order. */
    private val loggedErrors = mutableListOf<String>()
    private lateinit var previousHandler: (String, Throwable?, String) -> Unit

    @Before
    fun captureSilentErrors() {
        previousHandler = KolibriLog.taggedErrorHandler
        KolibriLog.taggedErrorHandler = { _, _, message -> loggedErrors.add(message) }
    }

    @After
    fun restoreHandler() {
        KolibriLog.taggedErrorHandler = previousHandler
    }

    // --- Fixtures ---

    private fun view() = ZoomableImageView(ApplicationProvider.getApplicationContext())

    private fun layer(id: String) = WallpaperLayerState(
        id = id,
        imageUri = "file:///data/$id.jpg",
        label = null,
    )

    private fun twoLayerState() = WallpaperState.multiLayer(listOf(layer("L0"), layer("L1")))

    private fun bitmap() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    // ===========================================
    // CANCELLATION — must propagate, must abort the loop
    // ===========================================

    @Test
    fun `cancelled decode propagates instead of being reported as an error`() = runTest {
        val binder = WallpaperViewBinder { _ ->
            throw CancellationException("render job superseded")
        }

        val thrown = runCatching { binder.bind(view(), twoLayerState()) }.exceptionOrNull()

        // The two guarantees that actually matter, both under the parallel decode:
        assertTrue(
            "cancellation must reach the caller, not the silentError branch — got $thrown",
            thrown is CancellationException
        )
        assertEquals(
            "a cancelled render is not a crash — nothing may be reported",
            emptyList<String>(),
            loggedErrors
        )
        // NOTE: the old `loadedUris.size == 1` assertion was intentionally dropped.
        // The decode is now parallel: all layers' `async` bodies are pre-launched
        // before awaitAll suspends, and a spontaneously-thrown CancellationException
        // from one child does NOT cancel its siblings (JobSupport treats a
        // CancellationException cause as handled), so every layer's load() runs
        // before the cancellation surfaces at the caller. The honest bound would be
        // `<= plan.layers.size`, which is trivially true and carries no signal — so
        // the count is not asserted at all. (In production, latest-wins cancellation
        // comes from an external job.cancel() that DOES propagate down and cancel
        // in-flight children — a different mechanism than this fake models, but one
        // that satisfies the same two invariants above.)
    }

    // ===========================================
    // REAL FAILURE — must stay caught and non-fatal
    // ===========================================

    @Test
    fun `layer whose decode throws is skipped and reported without aborting the rebuild`() =
        runTest {
            val view = view()
            val binder = WallpaperViewBinder { uri ->
                if (uri.toString().endsWith("L0.jpg")) throw IOException("unreadable")
                else DecodedWallpaperBitmap(bitmap(), 1, 8, 8)
            }

            binder.bind(view, twoLayerState())

            assertEquals("the surviving layer must still be added", 1, view.layerCount)
            assertEquals("the failed layer must be reported once", 1, loggedErrors.size)
            assertTrue(
                "the report must name the failed layer — got ${loggedErrors.first()}",
                loggedErrors.first().contains("L0")
            )
        }
}
