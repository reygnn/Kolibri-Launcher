package com.github.reygnn.kolibri_launcher.domain.model

import java.util.concurrent.atomic.AtomicLong

/**
 * Zustand eines einzelnen Wallpaper-Layers (Folie).
 *
 * Immutable – jede Änderung erzeugt eine neue Instanz.
 */
data class WallpaperLayerState(
    /** Unique ID für Identifikation */
    val id: String = newId(),

    /** Image URI as string (`content://` or `file://`). UI-side consumers parse it on demand. */
    val imageUri: String? = null,

    /** Zoom-Faktor (1.0 = Original) */
    val scale: Float = DEFAULT_SCALE,

    /** Horizontale Verschiebung in Pixel */
    val translateX: Float = 0f,

    /** Vertikale Verschiebung in Pixel */
    val translateY: Float = 0f,

    /**
     * The bitmap `inSampleSize` this layer's [scale]/[translateX]/[translateY]
     * were captured against — the decode downsample factor in force when the
     * transform was saved. Lets a later render-budget change compensate the
     * bitmap-absolute [scale] via the ratio `S_render / captureSampleSize`
     * (WALLPAPER_RENDER_RES_SPEC §4-Y). `null` = legacy transform with no
     * recorded factor; the loader backfills it from the original image
     * dimensions + the old 24 MP budget (spec §7).
     */
    val captureSampleSize: Int? = null
) {
    companion object {
        const val DEFAULT_SCALE = 1.0f

        // Thread-safe counter — layer states can be created concurrently
        // (e.g. parsed from JSON on an IO thread while the UI thread
        // also calls withAddedLayer).
        private val counter = AtomicLong(0)

        /**
         * Erzeugt eine neue, prozessweit eindeutige Layer-ID.
         * Thread-safe.
         */
        fun newId(): String =
            "layer_${System.currentTimeMillis()}_${counter.getAndIncrement()}"

    }

    /** Wurde das Bild transformiert (nicht mehr default)? */
    val isTransformed: Boolean
        get() = scale != DEFAULT_SCALE || translateX != 0f || translateY != 0f

    /** Hat dieses Layer ein Bild? */
    val hasImage: Boolean
        get() = imageUri != null

}

/**
 * Domain model for the whole wallpaper state.
 *
 * A wallpaper is ALWAYS represented as a list of [layers]. There is no
 * separate flat "single-layer" representation anymore (the legacy
 * `imageUri`/`scale`/`translateX`/`translateY`/`captureSampleSize` fields
 * were removed): a single-image wallpaper is simply a one-element [layers]
 * list, an empty list is [NONE].
 *
 * The single-vs-composite distinction that remains is a pure RENDER
 * strategy keyed on [layerCount], not a data-model mode:
 *   - `layerCount == 1` → the cheap path (decode the lone image, position it
 *     via the ImageView matrix, `file://` cache key).
 *   - `layerCount >= 2` → flatten/composite the layers into one bitmap
 *     (`composite://` cache key).
 * Consumers therefore branch on [layerCount], never on a stored flag.
 *
 * Immutable data class — every change produces a new instance.
 */
data class WallpaperState(
    /** The layers, bottom-most first. Empty = no wallpaper ([NONE]). */
    val layers: List<WallpaperLayerState> = emptyList(),
) {
    companion object {
        const val DEFAULT_SCALE = WallpaperLayerState.DEFAULT_SCALE

        /** Empty state — no wallpaper set. */
        val NONE = WallpaperState()

        /**
         * Builds a [WallpaperState] from a layer list. A one-element list is a
         * single-image wallpaper; two or more layers composite.
         */
        fun multiLayer(layers: List<WallpaperLayerState>): WallpaperState {
            return WallpaperState(layers = layers)
        }

        /**
         * Convenience builder for a single-image wallpaper — the canonical
         * one-element [layers] representation. Reads at call sites like the
         * removed flat constructor did, so a lone image never has to be
         * hand-wrapped into a list.
         */
        fun single(
            uri: String,
            scale: Float = DEFAULT_SCALE,
            translateX: Float = 0f,
            translateY: Float = 0f,
            captureSampleSize: Int? = null,
        ): WallpaperState = WallpaperState(
            layers = listOf(
                WallpaperLayerState(
                    imageUri = uri,
                    scale = scale,
                    translateX = translateX,
                    translateY = translateY,
                    captureSampleSize = captureSampleSize,
                )
            )
        )
    }

    // ===========================================
    // MODE / RENDER-STRATEGY DETECTION
    // ===========================================

    /** Number of layers (0 = no wallpaper). */
    val layerCount: Int
        get() = layers.size

    // ===========================================
    // DERIVED GETTERS
    // ===========================================

    /** Has the user configured a wallpaper? (at least one layer with an image) */
    val hasWallpaper: Boolean
        get() = layers.any { it.hasImage }

    /** Has any layer been transformed (moved/zoomed away from default)? */
    val isTransformed: Boolean
        get() = layers.any { it.isTransformed }

    /**
     * All image URIs this state references. Useful for orphan-file GC: files in
     * `wallpapers/` not present in this set are orphans and can be cleaned up.
     */
    val referencedUris: Set<String>
        get() = layers.mapNotNullTo(mutableSetOf()) { it.imageUri }

    // ===========================================
    // LAYER HELPERS
    // ===========================================

    /**
     * Gibt ein bestimmtes Layer zurück. Null-safe.
     */
    fun getLayer(index: Int): WallpaperLayerState? = layers.getOrNull(index)

    /**
     * Erstellt eine Kopie mit einem aktualisierten Layer.
     * Gibt den unveränderten State zurück wenn der Index ungültig ist.
     */
    fun withUpdatedLayer(index: Int, update: (WallpaperLayerState) -> WallpaperLayerState): WallpaperState {
        if (index !in layers.indices) return this
        val newLayers = layers.toMutableList()
        newLayers[index] = update(newLayers[index])
        return copy(layers = newLayers)
    }

    /**
     * Erstellt eine Kopie mit einem neuen Layer am Ende.
     */
    fun withAddedLayer(layer: WallpaperLayerState): WallpaperState {
        return copy(layers = layers + layer)
    }

    /**
     * Erstellt eine Kopie ohne das Layer an [index].
     */
    fun withRemovedLayer(index: Int): WallpaperState {
        if (index !in layers.indices) return this
        return copy(
            layers = layers.filterIndexed { i, _ -> i != index },
        )
    }

    /**
     * Erstellt eine Kopie mit vertauschten Layern.
     */
    fun withSwappedLayers(indexA: Int, indexB: Int): WallpaperState {
        if (indexA !in layers.indices || indexB !in layers.indices) return this
        val newLayers = layers.toMutableList()
        val temp = newLayers[indexA]
        newLayers[indexA] = newLayers[indexB]
        newLayers[indexB] = temp
        return copy(layers = newLayers)
    }
}