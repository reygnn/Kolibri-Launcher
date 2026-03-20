package com.github.reygnn.kolibri_launcher.domain.model

import android.graphics.BlendMode
import android.net.Uri

/**
 * Zustand eines einzelnen Wallpaper-Layers (Folie).
 *
 * Immutable – jede Änderung erzeugt eine neue Instanz.
 * Persistierbar über toMap() / fromMap().
 */
data class WallpaperLayerState(
    /** Unique ID für Identifikation */
    val id: String = "layer_${System.currentTimeMillis()}_${counter++}",

    /** URI zum Bild (content:// oder file://) */
    val imageUri: Uri? = null,

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
    val label: String? = null
) {
    companion object {
        const val DEFAULT_SCALE = 1.0f
        private var counter = 0L

        /**
         * Erstellt einen LayerState aus einer Map (Restore aus SharedPreferences).
         */
        fun fromMap(map: Map<String, Any?>): WallpaperLayerState {
            return WallpaperLayerState(
                id = map["id"] as? String ?: "layer_${System.currentTimeMillis()}_${counter++}",
                imageUri = (map["imageUri"] as? String)?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                scale = (map["scale"] as? Number)?.toFloat() ?: DEFAULT_SCALE,
                translateX = (map["translateX"] as? Number)?.toFloat() ?: 0f,
                translateY = (map["translateY"] as? Number)?.toFloat() ?: 0f,
                alpha = (map["alpha"] as? Number)?.toFloat() ?: 1.0f,
                blendModeName = (map["blendModeName"] as? String)?.takeIf { it.isNotEmpty() },
                isVisible = map["isVisible"] as? Boolean ?: true,
                label = (map["label"] as? String)?.takeIf { it.isNotEmpty() }
            )
        }
    }

    /** Wurde das Bild transformiert (nicht mehr default)? */
    val isTransformed: Boolean
        get() = scale != DEFAULT_SCALE || translateX != 0f || translateY != 0f

    /** Hat dieses Layer ein Bild? */
    val hasImage: Boolean
        get() = imageUri != null

    /**
     * BlendMode-Objekt (API 29+). Wird aus dem Namen aufgelöst.
     * null = Normal (SRC_OVER).
     */
    val blendMode: BlendMode?
        get() = blendModeName?.let { name ->
            try {
                BlendMode.valueOf(name)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    /**
     * Exportiert als Map für SharedPreferences / JSON.
     */
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "imageUri" to (imageUri?.toString() ?: ""),
        "scale" to scale,
        "translateX" to translateX,
        "translateY" to translateY,
        "alpha" to alpha,
        "blendModeName" to (blendModeName ?: ""),
        "isVisible" to isVisible,
        "label" to (label ?: "")
    )
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

    /** URI zum Bild – null = kein Custom Wallpaper. Wird ignoriert wenn layers nicht leer. */
    val imageUri: Uri? = null,

    /** Zoom-Faktor (Single-Layer) */
    val scale: Float = WallpaperLayerState.DEFAULT_SCALE,

    /** Horizontale Verschiebung (Single-Layer) */
    val translateX: Float = 0f,

    /** Vertikale Verschiebung (Single-Layer) */
    val translateY: Float = 0f,

    // --- Multi-Layer Felder ---

    /** Liste der Layer-States. Leer = Single-Layer-Modus. */
    val layers: List<WallpaperLayerState> = emptyList(),

    // --- Transient UI State ---

    /** Edit-Modus aktiv? (transient – nicht persistiert) */
    val isEditMode: Boolean = false
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
        return copy(layers = layers.filterIndexed { i, _ -> i != index })
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
     */
    fun toMultiLayer(): WallpaperState {
        if (isMultiLayer) return this
        if (!hasWallpaper) return this

        val singleLayer = WallpaperLayerState(
            imageUri = imageUri,
            scale = scale,
            translateX = translateX,
            translateY = translateY,
            label = "Layer 1"
        )

        return copy(
            // Single-Layer Felder bleiben für Fallback
            layers = listOf(singleLayer)
        )
    }

    // ===========================================
    // PERSISTENCE
    // ===========================================

    /**
     * Kopie ohne transiente UI-State (für Persistierung).
     */
    fun forPersistence(): WallpaperState = copy(isEditMode = false)
}