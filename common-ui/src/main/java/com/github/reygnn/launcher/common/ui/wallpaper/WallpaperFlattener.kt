package com.github.reygnn.launcher.common.ui.wallpaper

import com.github.reygnn.launcher.common.ui.LaunchTrace
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Produces the flattened display-mode composite (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4):
 * one SOFTWARE bitmap that the caller copies to HARDWARE and holds in the in-memory
 * [WallpaperCompositeCache]. There is no on-disk composite in v4 — no file, no WEBP, no store.
 *
 * Draws without a View (SPEC_NYX_REWRITE Stufe 2): the layers come from the same plan the live
 * view builds ([WallpaperViewDiff] from an empty view → [RebuildPlan.FullRebuild], so the same
 * filter and order), are placed with [WallpaperLayerPlacement] and drawn with
 * [WallpaperLayerPainter] — the live view's math, pinned bit-identical by
 * `WallpaperCompositorParityInstrumentedTest`, which also compares against the real live view on
 * screen. The only difference from the live path is the decode: SOFTWARE (`ARGB_8888`) bitmaps,
 * because the compose draws on a software `Canvas`, which cannot draw the HARDWARE bitmaps the
 * live display uses.
 *
 * Nothing runs on Main: the decodes run on [ioDispatcher] (in parallel, bounded like the live
 * binder), placement and compose on [defaultDispatcher]. If the caller is cancelled during the
 * compose, the finished software bitmap is left to the GC (a rare path, no leak).
 *
 * Returns a SOFTWARE bitmap — the caller (the caller's warm step) samples its
 * luminance, copies it to HARDWARE for the cache, and recycles this software temp — or `null`
 * if [state] has fewer than two layers, the size is invalid, or the flatten was partial
 * (all-or-nothing, §3: any per-layer decode failure yields `null` so no incomplete composite
 * is cached). A lone image (one layer) is not composited and not cached at all since §25 P4
 * ([flatten] returns null for it) — it decodes live via the render side's bounded loader.
 */
class WallpaperFlattener @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    /**
     * Flattens [state]'s layers into one software bitmap at [width]x[height]
     * (default: display resolution), or `null` if [state] has fewer than two
     * layers, the size is invalid, a layer could not be decoded, or nothing rendered.
     * The layers are placed for a view of exactly [width]x[height].
     */
    suspend fun flatten(
        state: WallpaperState,
        width: Int = context.resources.displayMetrics.widthPixels,
        height: Int = context.resources.displayMetrics.heightPixels,
    ): Bitmap? {
        if (state.layerCount < 2 || width <= 0 || height <= 0) return null
        // The live plan from an empty view: a multi-layer target is always a full rebuild.
        val plan = WallpaperViewDiff.diff(ViewLayerSnapshot.EMPTY, state) as? RebuildPlan.FullRebuild
            ?: return null
        return try {
            // Decode every layer in parallel, bounded like the live binder; awaitAll keeps the
            // plan order (z-order). All-or-nothing: one failed layer means no composite (§3).
            val decoded = coroutineScope {
                val gate = Semaphore(WallpaperViewBinder.DEFAULT_MAX_PARALLEL_DECODES)
                plan.layers
                    .map { spec -> async { gate.withPermit { loadSoftware(spec.imageUri) } } }
                    .awaitAll()
            }
            val layers = decoded.filterNotNull()
            if (layers.size != plan.layers.size) {
                layers.forEach { it.bitmap.recycle() }
                null
            } else {
                withContext(defaultDispatcher) { compose(layers, plan.updates, width, height) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): the parallel decodes +
            // compose are an allocation boundary (OOM extends Error → Throwable). A failed
            // flatten just means no composite this time.
            TimberWrapper.silentError(e, "Wallpaper flatten failed")
            null
        }
    }

    /**
     * Places the decoded layers for a [width]x[height] view and draws them onto a new software
     * bitmap. The decoded layer bitmaps belong to this call alone and are recycled afterwards.
     */
    private fun compose(
        decoded: List<DecodedWallpaperBitmap>,
        updates: List<LayerPropertyUpdate>,
        width: Int,
        height: Int,
    ): Bitmap {
        try {
            val layers = decoded.mapIndexed { index, d ->
                val placement = WallpaperLayerPlacement.place(
                    transform = updates.getOrNull(index)?.transform,
                    bitmapWidth = d.bitmap.width,
                    bitmapHeight = d.bitmap.height,
                    sampleSize = d.sampleSize,
                    originalWidth = d.originalWidth,
                    originalHeight = d.originalHeight,
                    viewWidth = width,
                    viewHeight = height,
                )
                WallpaperLayer(
                    bitmap = d.bitmap,
                    scale = placement.scale,
                    translateX = placement.translateX,
                    translateY = placement.translateY,
                )
            }
            // Info section (Stufe 1): the allocation and the draw, as `composeToBitmap` measured
            // it before; the decodes stay outside. Sync — the compose does not suspend.
            return LaunchTrace.section(LaunchTrace.Names.WALLPAPER_COMPOSE) {
                val result = createBitmap(width, height)
                WallpaperLayerPainter.draw(
                    canvas = Canvas(result),
                    layers = layers,
                    backgroundColor = Color.TRANSPARENT,
                    paint = WallpaperLayerPainter.newLayerPaint(),
                    matrix = Matrix(),
                )
                result
            }
        } finally {
            decoded.forEach { it.bitmap.recycle() }
        }
    }

    /** SOFTWARE decode of one layer source, matching the binder's loader contract. */
    private suspend fun loadSoftware(uri: Uri): DecodedWallpaperBitmap? =
        withContext(ioDispatcher) {
            try {
                decodeBoundedWallpaperBitmap(preferSoftware = true) {
                    context.contentResolver.openInputStream(uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Catch kept (Expected error, four-category frame): a decode is an allocation
                // boundary (OOM extends Error → Throwable); one unreadable layer means no flatten.
                TimberWrapper.silentError(e, "Software layer decode failed for flatten")
                null
            }
        }
}
