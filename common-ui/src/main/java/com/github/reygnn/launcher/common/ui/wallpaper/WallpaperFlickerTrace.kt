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
 * The counter is a monotonic value per process ([frames]); every affected frame raises it by one.
 * The CURRENT value is reported via [Trace.setCounter] (which SETS a counter value rather than
 * adding to it) on EVERY multi-layer frame while tracing, affected or not (3b-0, 12b) — so the
 * counter track exists as soon as anything is drawn in multi-layer mode, and a trace without it
 * unambiguously means "not instrumented / no multi-layer frame", never "nothing flickered".
 * An affected frame reports the old value first, then the raised one (12c): every process's first
 * reported value is thus its starting point, and max − min counts the cold-start frame too.
 * An evaluation takes the DIFFERENCE per process (max − min of the reported values, the first
 * reported value being the starting point; tools/nyx-flicker-eval.py), never a sum of values.
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
        // Report the starting point BEFORE raising it (3b-0, 12c): a fresh process starts at 0 and
        // its first multi-layer frame is usually affected (bitmaps still loading) — without this
        // first report the track would begin at 1 and max − min would miss the cold-start frame.
        Trace.setCounter(COUNTER, frames)
        if (hasLayerWithoutBitmap(layers)) {
            frames++
            Trace.setCounter(COUNTER, frames)
        }
    }

    /** True if at least one of [layers] has no drawable bitmap (null or recycled). Pure. */
    fun hasLayerWithoutBitmap(layers: List<WallpaperLayer>): Boolean =
        layers.any { layer -> layer.bitmap.let { it == null || it.isRecycled } }
}
