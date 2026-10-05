package com.github.reygnn.launcher.common.ui.wallpaper

import android.os.Trace

/**
 * The flicker measuring point for SPEC_NYX_REWRITE E3 (3b-0): a Trace counter of "affected"
 * frames, measurement only, no behaviour.
 *
 * An **affected frame**: in multi-layer mode, a frame is drawn in which at least one layer of the
 * shown state has no drawable bitmap (none loaded yet, or recycled) — i.e. that layer is missing
 * on screen. Counted ONCE per frame, not per missing layer.
 *
 * The counter is a monotonic value per process ([frames]); every affected frame raises it by one
 * and reports that value via [Trace.setCounter], which SETS a counter value rather than adding to
 * it. An evaluation therefore takes the DIFFERENCE between the start and the end of its window
 * (tools/nyx-flicker-measure.sh), never a sum of the reported values.
 *
 * Only while a tracer is attached ([Trace.isEnabled]) — no cost in everyday use. Called from
 * [ZoomableImageView.onDraw] on the main thread, so [frames] needs no synchronisation. Lives in the
 * shared view, so both apps are measured alike (for Kolibri, pure instrumentation).
 */
object WallpaperFlickerTrace {

    /** The counter's name in a trace. */
    const val COUNTER = "wallpaper_layer_missing_frames"

    private var frames = 0L

    /** One multi-layer frame is about to be drawn with [layers]. Main thread only. */
    fun onMultiLayerFrame(layers: List<WallpaperLayer>) {
        if (!Trace.isEnabled()) return
        if (!hasLayerWithoutBitmap(layers)) return
        frames++
        Trace.setCounter(COUNTER, frames)
    }

    /** True if at least one of [layers] has no drawable bitmap (null or recycled). Pure. */
    fun hasLayerWithoutBitmap(layers: List<WallpaperLayer>): Boolean =
        layers.any { layer -> layer.bitmap.let { it == null || it.isRecycled } }
}
