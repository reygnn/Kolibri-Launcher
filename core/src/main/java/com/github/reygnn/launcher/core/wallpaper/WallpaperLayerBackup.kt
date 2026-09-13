package com.github.reygnn.launcher.core.wallpaper

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Backup-Repräsentation eines einzelnen Wallpaper-Layers. Neutral (nur an
 * [WallpaperLayerState] gekoppelt) und daher in `:core` geteilt — beide Launcher
 * serialisieren Wallpaper-Layer in ihr eigenes Backup-Schema.
 *
 * == ZIP BACKUP (neu) ==
 * imageFileName enthält den relativen Pfad im ZIP-Archiv
 * (z.B. "wallpapers/layer_0.img"). Beim Import wird die Datei
 * extrahiert und in den internen Speicher kopiert.
 *
 * == JSON BACKUP (Legacy) ==
 * imageFileName ist null. imageUri enthält die direkte URI.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WallpaperLayerBackup(
    val id: String? = null,

    @JsonNames("image_uri")
    val imageUri: String? = null,

    /** Relativer Pfad der Bilddatei im ZIP-Archiv. null bei Legacy-JSON-Backups. */
    @JsonNames("image_file_name")
    val imageFileName: String? = null,

    val scale: Float = 1.0f,

    @JsonNames("translate_x")
    val translateX: Float = 0f,

    @JsonNames("translate_y")
    val translateY: Float = 0f,

    /**
     * The decode inSampleSize the transform (scale/translate) was captured
     * against — bitmap-absolute values are only meaningful paired with it
     * (WALLPAPER_RENDER_RES_SPEC §4-Y). Null = legacy backup (pre-field);
     * the render side backfills from the original image dims. Persisting it
     * keeps a zoomed/panned wallpaper faithful across a backup round-trip.
     */
    @JsonNames("capture_sample_size")
    val captureSampleSize: Int? = null,
) {
    fun toLayerState(): WallpaperLayerState {
        return WallpaperLayerState(
            // Use newId() (atomic-counter suffix) rather than a bare
            // timestamp so a multi-layer legacy backup with all-null ids
            // restored in the same millisecond can't collide.
            id = id ?: WallpaperLayerState.newId(),
            imageUri = imageUri?.takeIf { it.isNotEmpty() },
            scale = scale,
            translateX = translateX,
            translateY = translateY,
            captureSampleSize = captureSampleSize,
        )
    }

    companion object {
        fun fromLayerState(state: WallpaperLayerState): WallpaperLayerBackup {
            return WallpaperLayerBackup(
                id = state.id,
                imageUri = state.imageUri,
                scale = state.scale,
                translateX = state.translateX,
                translateY = state.translateY,
                captureSampleSize = state.captureSampleSize,
            )
        }
    }
}
