package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.nyx_launcher.home.model.FabPosition
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's full backup engine (Scope B, nyx-local): a ZIP container
 * (`backup.json` + `wallpapers/ image` blobs) over the [NyxBackup] schema, composing
 * Nyx's repos + the shared wallpaper file/state layer. Assembler + ZIP-I/O in one
 * (Nyx's surface is small enough not to warrant Kolibri's serializer/assembler/impl
 * three-way split).
 *
 * Callers own the streams (the settings UI opens the SAF Uri); this stays
 * ContentResolver-free and testable. Blob restore reuses the shared
 * [WallpaperFileManager.copyFromInputStream] (extract → internal file), so imported
 * layers land in internal storage exactly like a fresh pick.
 */
@Singleton
class NyxBackupManager @Inject constructor(
    private val homeLayoutRepository: HomeLayoutRepository,
    private val preferences: PreferencesRepository,
    private val displaySettings: WallpaperDisplaySettings,
    private val wallpaperRepository: WallpaperRepository,
    private val fabPositionStore: NyxFabPositionStore,
    private val fileManager: WallpaperFileManager,
    private val serializer: NyxBackupSerializer,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Writes a full backup ZIP to [out]. Returns true on success. */
    suspend fun export(out: OutputStream, appVersion: String, timestamp: Long): Boolean =
        withContext(ioDispatcher) {
            try {
                val layout = homeLayoutRepository.layout().first().toDto()
                val prefs = NyxBackupPrefs(
                    monochromeIcons = preferences.monochromeIcons().first(),
                    showAlarm = preferences.showAlarmFlow.first(),
                    showCalendarEvent = preferences.showCalendarEventFlow.first(),
                    scrimAlpha = displaySettings.wallpaperScrimAlphaStateFlow.first(),
                    backdrop = displaySettings.wallpaperBackdropFlow.first().name,
                    surfaceMode = displaySettings.wallpaperSurfaceModeFlow.first().name,
                    fabXFraction = fabPositionStore.fabPositionFlow.first().xFraction,
                    fabYFraction = fabPositionStore.fabPositionFlow.first().yFraction,
                )
                // Layers + their blob file names. Only file:// layers with an existing
                // file get a blob entry; the imageFileName ties manifest ↔ blob.
                val state = wallpaperRepository.getWallpaperStateSync()
                val layerBlobs = ArrayList<Pair<String, File>>() // entryName -> source file
                val layers = state.layers.mapIndexedNotNull { index, layer ->
                    val backup = WallpaperLayerBackup.fromLayerState(layer)
                    val file = layer.imageUri?.let { localFileOrNull(it) }
                    if (file != null) {
                        val entryName = "wallpapers/layer_$index.img"
                        layerBlobs += entryName to file
                        backup.copy(imageFileName = entryName)
                    } else {
                        backup
                    }
                }
                val backup = NyxBackup(
                    timestamp = timestamp,
                    appVersion = appVersion,
                    layout = layout,
                    prefs = prefs,
                    wallpaperLayers = layers,
                )

                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(serializer.serialize(backup).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    // Dedup by absolute path so shared files reuse one blob entry.
                    val written = HashSet<String>()
                    for ((entryName, file) in layerBlobs) {
                        if (!written.add(file.absolutePath)) continue
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                true
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Nyx backup export failed")
                false
            }
        }

    /** Reads a backup ZIP from [inp] and applies it per [options]. */
    suspend fun import(inp: InputStream, options: NyxBackupOptions): ImportResult =
        withContext(ioDispatcher) {
            try {
                // Extract: manifest + each blob (copied to internal storage now, so
                // the restored URIs are already internal file://).
                var manifest: String? = null
                val extracted = HashMap<String, String>() // entryName -> internal uri
                ZipInputStream(inp).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == MANIFEST) {
                            manifest = zip.readBytes().toString(Charsets.UTF_8)
                        } else if (entry.name.startsWith(WALLPAPER_DIR)) {
                            // copyFromInputStream reads to entry-end without closing the zip stream.
                            fileManager.copyFromInputStream(zip)?.let { extracted[entry!!.name] = it.toString() }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                val backup = manifest?.let { serializer.deserialize(it) } ?: return@withContext ImportResult.InvalidData

                if (options.importLayout) {
                    backup.layout?.toDomain()?.let { homeLayoutRepository.save(it) }
                }
                if (options.importSettings) applyPrefs(backup.prefs)
                if (options.importWallpaper) restoreWallpaper(backup.wallpaperLayers, extracted)

                ImportResult.Success
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Nyx backup import failed")
                ImportResult.InvalidData
            }
        }

    private suspend fun applyPrefs(prefs: NyxBackupPrefs?) {
        prefs ?: return
        prefs.monochromeIcons?.let { preferences.setMonochromeIcons(it) }
        prefs.showAlarm?.let { preferences.setShowAlarm(it) }
        prefs.showCalendarEvent?.let { preferences.setShowCalendarEvent(it) }
        prefs.scrimAlpha?.let { displaySettings.setWallpaperScrimAlpha(it) }
        prefs.backdrop?.toEnumOrNull<WallpaperBackdrop>()?.let { displaySettings.setWallpaperBackdrop(it) }
        prefs.surfaceMode?.toEnumOrNull<WallpaperSurfaceMode>()?.let { displaySettings.setWallpaperSurfaceMode(it) }
        if (prefs.fabXFraction != null && prefs.fabYFraction != null) {
            fabPositionStore.saveFabPosition(FabPosition(prefs.fabXFraction, prefs.fabYFraction))
        }
    }

    private suspend fun restoreWallpaper(layers: List<WallpaperLayerBackup>, extracted: Map<String, String>) {
        if (layers.isEmpty()) return
        // Rebind each layer's imageFileName to its freshly-extracted internal URI.
        // A layer whose blob is missing/failed is dropped (all-or-nothing per layer).
        val restored = layers.mapNotNull { layer ->
            val uri = layer.imageFileName?.let { extracted[it] } ?: layer.imageUri
            uri?.let {
                WallpaperLayerState(
                    id = layer.id ?: WallpaperLayerState.newId(),
                    imageUri = it,
                    scale = layer.scale,
                    translateX = layer.translateX,
                    translateY = layer.translateY,
                )
            }
        }
        if (restored.isNotEmpty()) {
            wallpaperRepository.saveWallpaperState(WallpaperState.multiLayer(restored))
        }
    }

    /** file:// internal image → its [File], or null (skip non-file / missing). */
    private fun localFileOrNull(uriString: String): File? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return null
        val file = uri.path?.let { File(it) }
        return file?.takeIf { it.exists() }
    }

    private inline fun <reified E : Enum<E>> String.toEnumOrNull(): E? =
        runCatching { enumValueOf<E>(this) }.getOrNull()

    private companion object {
        const val MANIFEST = "backup.json"
        const val WALLPAPER_DIR = "wallpapers/"
    }
}
