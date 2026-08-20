package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Produces the flattened display-mode composite (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4):
 * one SOFTWARE bitmap that the caller copies to HARDWARE and holds in the in-memory
 * [WallpaperCompositeCache]. There is no on-disk composite in v4 — no file, no WEBP, no store.
 *
 * Reuses the LIVE render path — [WallpaperViewBinder] on a detached
 * [ZoomableImageView] — so the composite is faithful to what the multi-layer view
 * shows (the parity test measured mean 0.11 / max 1 across all blend modes). The
 * only difference from the live path is the bitmap loader: it decodes SOFTWARE
 * (`ARGB_8888`) bitmaps, because `composeToBitmap` composes on a software `Canvas`
 * that cannot draw the HARDWARE bitmaps the live display uses.
 *
 * Returns a SOFTWARE bitmap — the caller (`WallpaperDelegate.warmComposite`) samples its
 * luminance, copies it to HARDWARE for the cache, and recycles this software temp — or `null`
 * if [state] is not multi-layer, the size is invalid, or the flatten was partial (all-or-nothing,
 * §3: any per-layer decode failure yields `null` so no incomplete composite is cached).
 */
class WallpaperFlattener @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    /**
     * Flattens [state]'s layers into one software bitmap at [width]x[height]
     * (default: display resolution), or `null` if [state] is not multi-layer, the
     * size is invalid, or nothing rendered. View construction/mutation runs on the
     * Main thread; the binder decodes off-Main internally.
     */
    suspend fun flatten(
        state: WallpaperState,
        width: Int = context.resources.displayMetrics.widthPixels,
        height: Int = context.resources.displayMetrics.heightPixels,
    ): Bitmap? {
        if (!state.isMultiLayer || width <= 0 || height <= 0) return null
        return withContext(mainDispatcher) {
            try {
                val view = ZoomableImageView(context).apply {
                    isEditMode = false
                    measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    layout(0, 0, width, height)
                }
                // Same binder the live view uses -> identical decode/transform/blend
                // logic, only with a software loader. Track per-layer decode failures:
                // a partial composite (one layer skipped) is a decodable-but-INCOMPLETE
                // artifact, and it must NOT reach the cache (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC
                // §3, all-or-nothing). parseWallpaperState already drops missing-file layers,
                // so a null here is a TRANSIENT decode failure — returning null makes the warm
                // skip caching and re-flatten on the next miss, exactly like the live path heals.
                // Only a VISIBLE layer's decode failure makes the composite INCOMPLETE: drawLayers
                // skips invisible layers, so a hidden layer failing to decode changes no pixels and
                // must NOT block the cache (spec §3b, review #2). The binder decodes every
                // image-bearing layer regardless of visibility, so filter here.
                val visibleUris = state.layers.filter { it.isVisible }.mapNotNull { it.imageUri }.toSet()
                val anyLayerFailed = AtomicBoolean(false)
                val binder = WallpaperViewBinder { uri ->
                    loadSoftware(uri).also {
                        if (it == null && uri.toString() in visibleUris) anyLayerFailed.set(true)
                    }
                }
                binder.bind(view, state)
                val composite = view.composeToBitmap(width, height)
                if (anyLayerFailed.get()) {
                    composite?.recycle()
                    null
                } else {
                    composite
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable: the parallel decodes + compose are an allocation
                // boundary (OOM). A failed flatten just means no composite this time.
                TimberWrapper.silentError(e, "Wallpaper flatten failed")
                null
            }
        }
    }

    /**
     * HARDWARE decode of a single-layer wallpaper image for the display cache (AUDIT-20 F15).
     * Unlike [flatten] — which composes SOFTWARE layers the caller copies to HARDWARE — a lone
     * image needs no compositing: it is decoded straight to HARDWARE, matching the render side's
     * config ([com.github.reygnn.kolibri_launcher.ui.home.HomeFragment] `loadBitmapFromUri`) so the
     * entry is a drop-in cache hit, and the render positions it via the ImageView matrix. Returns
     * null on decode failure; the caller then skips caching and the render path re-decodes on the
     * next miss.
     *
     * Owns the `Uri` parse + `contentResolver`, so callers (the delegate) stay Uri-free and
     * JVM-testable — the decode itself is real-runtime work, pinned by the instrumented decoder test.
     */
    suspend fun decodeSingle(uriString: String): DecodedWallpaperBitmap? =
        withContext(Dispatchers.IO) {
            try {
                decodeBoundedWallpaperBitmap {
                    context.contentResolver.openInputStream(uriString.toUri())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Single-layer decode failed for refill")
                null
            }
        }

    /** SOFTWARE decode of one layer source, matching the binder's loader contract. */
    private suspend fun loadSoftware(uri: Uri): DecodedWallpaperBitmap? =
        withContext(Dispatchers.IO) {
            try {
                decodeBoundedWallpaperBitmap(preferSoftware = true) {
                    context.contentResolver.openInputStream(uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Software layer decode failed for flatten")
                null
            }
        }
}
