package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.core.toEnumOrNull
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

    /** Deckkraft: 0.0 (transparent) bis 1.0 (deckend) */
    val alpha: Float = 1.0f,

    /** Blend-Modus Name (für Persistierung). null = Normal (SRC_OVER) */
    val blendModeName: String? = null,

    /** Sichtbarkeit */
    val isVisible: Boolean = true,

    /** Optionaler Label (z.B. "Oben", "Unten") */
    val label: String? = null,

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

    /**
     * Domain blend mode resolved from the persisted [blendModeName].
     * `null` = Normal (SRC_OVER) or an unknown name. UI consumers map this to
     * `android.graphics.BlendMode` via `WallpaperBlendMode.toAndroidBlendMode()`.
     */
    val blendMode: WallpaperBlendMode?
        get() = blendModeName.toEnumOrNull<WallpaperBlendMode>()

}

/**
 * Domain Model für den gesamten Wallpaper-Zustand.
 *
 * == BACKWARD COMPATIBILITY ==
 * Single-Layer (wie bisher):
 *   WallpaperState(imageUri = uri, scale = 2.0f, ...)
 *   → layers bleibt leer, hasWallpaper/imageUri/scale/etc. funktionieren wie vorher.
 *
 * Multi-Layer (neu):
 *   WallpaperState(layers = listOf(layer1, layer2, ...))
 *   → imageUri gibt Layer 0 zurück (Fallback für alten Code).
 *
 * Immutable data class – jede Änderung erzeugt eine neue Instanz.
 */
data class WallpaperState(
    // --- Single-Layer Felder (Backward Compatibility) ---
    //
    // Invariant: when [layers] is non-empty, ALL four single-layer fields
    // MUST hold their default values (null URI, default scale, zero
    // translates). The codebase keys every read on [isMultiLayer] and
    // never falls back to these fields in multi-mode, so any non-default
    // value here would be a shadow state — invisible in the UI but counted
    // by [referencedUris] and exported by callers that walk both branches.
    // [toMultiLayer] enforces this when migrating; constructed states
    // should respect it too.

    /**
     * Image URI as string — `null` = no custom wallpaper. MUST be `null` when [layers]
     * is non-empty.
     */
    val imageUri: String? = null,

    /** Zoom-Faktor (Single-Layer). MUSS Default sein wenn layers nicht leer. */
    val scale: Float = WallpaperLayerState.DEFAULT_SCALE,

    /** Horizontale Verschiebung (Single-Layer). MUSS 0f sein wenn layers nicht leer. */
    val translateX: Float = 0f,

    /** Vertikale Verschiebung (Single-Layer). MUSS 0f sein wenn layers nicht leer. */
    val translateY: Float = 0f,

    // --- Multi-Layer Felder ---

    /** Liste der Layer-States. Leer = Single-Layer-Modus. */
    val layers: List<WallpaperLayerState> = emptyList(),

    /**
     * Single-layer twin of [WallpaperLayerState.captureSampleSize]: the decode
     * downsample factor the single-layer [scale]/[translateX]/[translateY] were
     * captured against. MUST be `null` when [layers] is non-empty. See spec §4-Y.
     */
    val captureSampleSize: Int? = null,
) {
    companion object {
        const val DEFAULT_SCALE = WallpaperLayerState.DEFAULT_SCALE

        /** Leerer Zustand – kein Wallpaper gesetzt */
        val NONE = WallpaperState()

        /**
         * Erstellt einen Multi-Layer WallpaperState aus einer Layer-Liste.
         */
        fun multiLayer(layers: List<WallpaperLayerState>): WallpaperState {
            return WallpaperState(layers = layers)
        }
    }

    // ===========================================
    // MODE DETECTION
    // ===========================================

    /** True wenn Multi-Layer aktiv (mindestens ein Layer definiert) */
    val isMultiLayer: Boolean
        get() = layers.isNotEmpty()

    /** Anzahl der Layer (0 im Single-Layer-Modus) */
    val layerCount: Int
        get() = layers.size

    // ===========================================
    // BACKWARD COMPATIBLE GETTERS
    // ===========================================

    /**
     * Hat der User ein Wallpaper konfiguriert?
     * Multi-Layer: Mindestens ein Layer mit Bild.
     * Single-Layer: imageUri != null.
     */
    val hasWallpaper: Boolean
        get() = if (isMultiLayer) layers.any { it.hasImage } else imageUri != null

    /**
     * Wurde das Bild transformiert?
     * Multi-Layer: Irgendein Layer transformiert.
     * Single-Layer: Original-Logik.
     */
    val isTransformed: Boolean
        get() = if (isMultiLayer) {
            layers.any { it.isTransformed }
        } else {
            scale != DEFAULT_SCALE || translateX != 0f || translateY != 0f
        }

    /**
     * Alle Bild-URIs, die dieser State referenziert — Single-Layer- und Multi-Layer-Fall kombiniert.
     * Nützlich für Orphan-File-GC: Dateien in `wallpapers/`, die nicht in diesem Set
     * vorkommen, sind Waisen und können weggeräumt werden.
     */
    val referencedUris: Set<String>
        get() {
            val set = mutableSetOf<String>()
            imageUri?.let { set.add(it) }
            for (layer in layers) {
                layer.imageUri?.let { set.add(it) }
            }
            return set
        }

    // ===========================================
    // MULTI-LAYER HELPERS
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

    // ===========================================
    // MIGRATION: SINGLE → MULTI
    // ===========================================

    /**
     * Konvertiert einen Single-Layer State in einen Multi-Layer State.
     * Nützlich beim ersten Mal "Add Layer".
     *
     * Setzt die Single-Layer-Felder explizit auf Defaults zurück. Aus
     * Sicht des Konsumenten ist der State danach komplett durch [layers]
     * beschrieben; die alten Felder zu lassen wäre Schatten-State, das
     * niemand liest, aber [referencedUris] und JSON-Exporter mitziehen.
     */
    fun toMultiLayer(): WallpaperState {
        if (isMultiLayer) return this
        if (!hasWallpaper) return this

        val singleLayer = WallpaperLayerState(
            imageUri = imageUri,
            scale = scale,
            translateX = translateX,
            translateY = translateY,
            captureSampleSize = captureSampleSize,
            label = "Layer 1"
        )

        return copy(
            imageUri = null,
            scale = DEFAULT_SCALE,
            translateX = 0f,
            translateY = 0f,
            captureSampleSize = null,
            layers = listOf(singleLayer),
        )
    }
}